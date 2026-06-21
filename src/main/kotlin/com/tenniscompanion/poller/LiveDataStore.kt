package com.tenniscompanion.poller

import com.tenniscompanion.api.LiveMatchDto
import com.tenniscompanion.api.PlayerSideDto
import com.tenniscompanion.api.RankingRowDto
import com.tenniscompanion.insight.MatchFacts
import com.tenniscompanion.integration.MatchWeighting
import com.tenniscompanion.reconcile.NameNormalizer
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * The "fan-out" layer: pollers write here (DB durable snapshot + Redis cache), the serving API reads
 * here (Redis first, Postgres fallback). The upstream API is never touched on the read path.
 *
 * All writes target the unified `matches` and `rankings` tables. Redis is a pure read cache;
 * Postgres is the canonical store for all sources.
 */
@Repository
class LiveDataStore(
    private val jdbc: JdbcTemplate,
    private val namedJdbc: NamedParameterJdbcTemplate,
    private val redis: StringRedisTemplate,
    private val mapper: ObjectMapper,
    private val weighting: MatchWeighting,
) {
    private val cacheTtl = Duration.ofHours(24)

    // --- live matches ---

    fun saveLiveMatches(source: String, matches: List<LiveMatchDto>) {
        matches.forEach { upsertMatch(source, it) }
        // Matches no longer in the live feed have finished
        val ids = matches.map { it.externalId }
        if (ids.isEmpty()) {
            jdbc.update("UPDATE matches SET status='finished', last_polled_at=now() WHERE source=? AND status='live'", source)
        } else {
            namedJdbc.update(
                "UPDATE matches SET status='finished', last_polled_at=now() WHERE source=:src AND status='live' AND external_id NOT IN (:ids)",
                MapSqlParameterSource().addValue("src", source).addValue("ids", ids),
            )
        }
        redis.opsForValue().set(LIVE_KEY, mapper.writeValueAsString(matches), cacheTtl)
    }

    /** Today's completed matches (from the fixtures-backed recent job). Upsert + a separate cache key. */
    fun saveRecentMatches(source: String, matches: List<LiveMatchDto>) {
        matches.forEach { upsertMatch(source, it) }
        redis.opsForValue().set(RECENT_KEY, mapper.writeValueAsString(matches), cacheTtl)
    }

    private fun upsertMatch(source: String, m: LiveMatchDto) {
        jdbc.update(
            """
            INSERT INTO matches(source, external_id, status, round, surface, tour, category, qualifying, tourney_name,
                                player1_name, player2_name, player1_id, player2_id, score_detail, start_time, last_polled_at, serve)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?::uuid,?::uuid,?::jsonb,?,now(),?)
            ON CONFLICT (source, external_id) WHERE external_id IS NOT NULL DO UPDATE SET
              status=EXCLUDED.status, round=EXCLUDED.round, surface=EXCLUDED.surface, tour=EXCLUDED.tour,
              category=EXCLUDED.category, qualifying=EXCLUDED.qualifying, tourney_name=EXCLUDED.tourney_name,
              player1_name=EXCLUDED.player1_name, player2_name=EXCLUDED.player2_name,
              player1_id=EXCLUDED.player1_id, player2_id=EXCLUDED.player2_id,
              score_detail=EXCLUDED.score_detail, start_time=EXCLUDED.start_time, last_polled_at=now(),
              serve=EXCLUDED.serve
            """.trimIndent(),
            source, m.externalId, m.status, m.round, m.surface, m.tour, m.category, m.qualifying, m.tournamentName,
            m.player1.name, m.player2.name, m.player1.playerId?.toString(), m.player2.playerId?.toString(),
            m.score?.let { mapper.writeValueAsString(it) }, m.startTime?.atOffset(ZoneOffset.UTC),
            m.serve,
        )
        if (m.status == "finished") resolveWinner(source, m)
    }

    /** When a match finishes, populate winner/loser from the score map (idempotent via WHERE winner_id IS NULL). */
    private fun resolveWinner(source: String, m: LiveMatchDto) {
        val side = MatchFacts.winnerOf(m.score) ?: return
        val winner = if (side == "home") m.player1 else m.player2
        val loser = if (side == "home") m.player2 else m.player1
        val scoreText = MatchFacts.scoreFrom(m.score, side).replace(", ", " ").takeIf { it.isNotBlank() }
        jdbc.update(
            """
            UPDATE matches SET winner_id=?::uuid, loser_id=?::uuid, winner_name=?, loser_name=?, score=?
            WHERE source=? AND external_id=? AND winner_id IS NULL
            """.trimIndent(),
            winner.playerId?.toString(), loser.playerId?.toString(), winner.name, loser.name, scoreText, source, m.externalId,
        )
    }

    fun liveMatches(source: String): List<LiveMatchDto> = weightedSort(
        source,
        redis.opsForValue().get(LIVE_KEY)?.let { mapper.readValue(it, Array<LiveMatchDto>::class.java).toList() }
            ?: readMatches(source, "live", null),
    )

    /** Completed matches from today (UTC). Cache first, else finished rows polled today. */
    fun recentMatches(source: String): List<LiveMatchDto> = weightedSort(
        source,
        redis.opsForValue().get(RECENT_KEY)?.let { mapper.readValue(it, Array<LiveMatchDto>::class.java).toList() }
            ?: readMatches(source, "finished", LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC)),
    )

    /** Order by importance (Grand Slam > tour > Challenger > …, late rounds first), then recency. */
    private fun weightedSort(source: String, matches: List<LiveMatchDto>): List<LiveMatchDto> {
        val ranks = rankByPlayerId()
        val tournamentIds = tournamentIdsByFoldedName(source)
        // The feed has no country; backfill it (like rank) so the list cards can show a flag, not just the detail view.
        val countries = countryByIds(matches.flatMap { listOfNotNull(it.player1.playerId, it.player2.playerId) }.distinct())
        return matches
            .map {
                it.copy(
                    tier = weighting.tierOf(it.tournamentName, it.category).name,
                    tournamentId = resolveTournamentId(tournamentIds, it.tournamentName),
                    player1 = it.player1.copy(
                        rank = it.player1.rank ?: it.player1.playerId?.let { id -> ranks[id] },
                        country = it.player1.country ?: it.player1.playerId?.let { id -> countries[id] },
                    ),
                    player2 = it.player2.copy(
                        rank = it.player2.rank ?: it.player2.playerId?.let { id -> ranks[id] },
                        country = it.player2.country ?: it.player2.playerId?.let { id -> countries[id] },
                    ),
                )
            }
            .sortedWith(
                compareByDescending<LiveMatchDto> { weighting.weight(it.tournamentName, it.category, it.round, it.qualifying) }
                    .thenByDescending { it.startTime ?: Instant.MIN },
            )
    }

    /** foldedName → tournaments.id for the source. Built once per sorted read; the set is small (current events). */
    private fun tournamentIdsByFoldedName(source: String): Map<String, Long> {
        val rows = jdbc.query(
            "SELECT id, name FROM tournaments WHERE source = ? AND name IS NOT NULL",
            { rs, _ -> NameNormalizer.fold(rs.getString("name")) to rs.getLong("id") },
            source,
        )
        val out = LinkedHashMap<String, Long>()
        for ((folded, id) in rows) if (folded.isNotBlank()) out.putIfAbsent(folded, id)
        return out
    }

    /** Match a denormalized tournament name to its id: exact folded match first, else containment either way. */
    private fun resolveTournamentId(byName: Map<String, Long>, tournamentName: String?): Long? {
        val f = tournamentName?.let { NameNormalizer.fold(it) }?.takeIf { it.isNotBlank() } ?: return null
        byName[f]?.let { return it }
        return byName.entries.firstOrNull { f.contains(it.key) || it.key.contains(f) }?.value
    }

    /** player UUID → current rank, from the latest cached ATP/WTA rankings. */
    private fun rankByPlayerId(): Map<UUID, Int> {
        val map = HashMap<UUID, Int>()
        for (tour in listOf("ATP", "WTA")) {
            for (r in rankings(tour, 500)) r.playerId?.let { map[it] = r.rank }
        }
        return map
    }

    /**
     * Today's matches (live + recently finished) belonging to the named tournament, importance-sorted.
     * Matches carry only a denormalized `tournamentName` (no FK), so we match on the accent/case-folded
     * name — containment either way absorbs a tour prefix (feed "WTA Roland Garros" ⊇ "Roland Garros").
     */
    fun matchesForTournament(source: String, tournamentName: String): List<LiveMatchDto> {
        val target = NameNormalizer.fold(tournamentName)
        if (target.isBlank()) return emptyList()
        val byId = LinkedHashMap<String, LiveMatchDto>()
        for (m in liveMatches(source) + recentMatches(source)) {
            val name = m.tournamentName?.let { NameNormalizer.fold(it) } ?: continue
            if (name == target || name.contains(target) || target.contains(name)) byId.putIfAbsent(m.externalId, m)
        }
        return byId.values.toList()
    }

    // --- single match (dedicated match view) ---

    private fun find(source: String, externalId: String): LiveMatchDto? =
        (liveMatches(source).asSequence() + recentMatches(source).asSequence()).firstOrNull { it.externalId == externalId }

    /** Status for the given match (cheap), or null if we don't know it — used to lock chat when finished. */
    fun matchStatus(source: String, externalId: String): String? = find(source, externalId)?.status

    /** The match enriched for the detail view: country stamped for both players + an approx endedAt when finished. */
    fun matchDetail(source: String, externalId: String): LiveMatchDto? {
        val m = find(source, externalId) ?: return null
        val ids = listOfNotNull(m.player1.playerId, m.player2.playerId)
        val countries = if (ids.isEmpty()) emptyMap() else countryByIds(ids)
        return m.copy(
            player1 = m.player1.copy(country = m.player1.country ?: m.player1.playerId?.let { countries[it] }),
            player2 = m.player2.copy(country = m.player2.country ?: m.player2.playerId?.let { countries[it] }),
            endedAt = if (m.status == "finished") lastPolledAt(source, externalId) else null,
        )
    }

    private fun countryByIds(ids: List<UUID>): Map<UUID, String> {
        if (ids.isEmpty()) return emptyMap()
        return namedJdbc.query(
            "SELECT id, country_code FROM players WHERE id IN (:ids) AND country_code IS NOT NULL",
            MapSqlParameterSource("ids", ids),
        ) { rs, _ -> rs.getObject("id", UUID::class.java) to rs.getString("country_code") }.toMap()
    }

    private fun lastPolledAt(source: String, externalId: String): Instant? =
        jdbc.query(
            "SELECT last_polled_at FROM matches WHERE source = ? AND external_id = ?",
            { rs, _ -> rs.getTimestamp("last_polled_at")?.toInstant() },
            source, externalId,
        ).firstOrNull()

    private fun readMatches(source: String, status: String, since: Instant?): List<LiveMatchDto> {
        val sql = StringBuilder(
            """
            SELECT external_id, status, tourney_name AS tournament_name, round, surface, tour, category, qualifying,
                   player1_name, player2_name, player1_id, player2_id,
                   score_detail::text AS score_json, start_time, serve
            FROM matches WHERE source = ? AND status = ?
            """.trimIndent(),
        )
        val args = mutableListOf<Any?>(source, status)
        if (since != null) { sql.append(" AND last_polled_at >= ?"); args.add(OffsetDateTime.ofInstant(since, ZoneOffset.UTC)) }
        sql.append(" ORDER BY start_time DESC LIMIT 100")
        return jdbc.query(sql.toString(), { rs, _ ->
            LiveMatchDto(
                externalId = rs.getString("external_id"),
                status = rs.getString("status"),
                tournamentName = rs.getString("tournament_name"),
                round = rs.getString("round"),
                surface = rs.getString("surface"),
                tour = rs.getString("tour"),
                category = rs.getString("category"),
                qualifying = rs.getBoolean("qualifying"),
                player1 = PlayerSideDto(rs.getString("player1_name"), rs.getObject("player1_id", UUID::class.java), null, null),
                player2 = PlayerSideDto(rs.getString("player2_name"), rs.getObject("player2_id", UUID::class.java), null, null),
                score = rs.getString("score_json")?.let { mapper.readValue(it, Map::class.java) as Map<String, Any?> },
                startTime = rs.getTimestamp("start_time")?.toInstant(),
                serve = rs.getString("serve"),
            )
        }, *args.toTypedArray())
    }

    // --- rankings ---

    fun saveRankings(tour: String, rows: List<RankingRowDto>, capturedAt: Instant) {
        // Rankings are never legitimately empty, so an empty set means the upstream call failed or
        // returned nothing useful — skip the write (and the cache overwrite) to keep last-good data.
        if (rows.isEmpty()) return
        val ts = OffsetDateTime.ofInstant(capturedAt, ZoneOffset.UTC)
        for (r in rows) {
            jdbc.update(
                """
                INSERT INTO rankings(source, ranking_date, tour, rank, player_id, external_name, points, captured_at)
                VALUES ('api-tennis', CURRENT_DATE, ?,?,?::uuid,?,?,?)
                ON CONFLICT (source, ranking_date, tour, rank) WHERE source <> 'sackmann' DO UPDATE SET
                  player_id=EXCLUDED.player_id, external_name=EXCLUDED.external_name, points=EXCLUDED.points,
                  captured_at=EXCLUDED.captured_at
                """.trimIndent(),
                tour, r.rank, r.playerId?.toString(), r.name, r.points, ts,
            )
        }
        redis.opsForValue().set(rankKey(tour), mapper.writeValueAsString(rows), cacheTtl)
    }

    fun rankings(tour: String, limit: Int): List<RankingRowDto> {
        val cached = redis.opsForValue().get(rankKey(tour))
            ?.let { mapper.readValue(it, Array<RankingRowDto>::class.java).toList() }
        if (cached != null) return cached.take(limit)
        return jdbc.query(
            """
            SELECT rank, player_id, external_name, points FROM rankings
            WHERE source='api-tennis' AND tour=?
              AND ranking_date=(SELECT MAX(ranking_date) FROM rankings WHERE source='api-tennis' AND tour=?)
            ORDER BY rank LIMIT ?
            """.trimIndent(),
            { rs, _ ->
                RankingRowDto(
                    rs.getInt("rank"),
                    rs.getObject("player_id", UUID::class.java),
                    rs.getString("external_name"),
                    null,
                    (rs.getObject("points") as? Number)?.toInt(),
                )
            },
            tour, tour, limit,
        )
    }

    private fun rankKey(tour: String) = "rankings:${tour.lowercase()}"

    companion object {
        private const val LIVE_KEY = "scores:live"
        private const val RECENT_KEY = "scores:recent"
    }
}
