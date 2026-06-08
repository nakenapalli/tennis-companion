package com.tenniscompanion.poller

import com.tenniscompanion.api.LiveMatchDto
import com.tenniscompanion.api.PlayerSideDto
import com.tenniscompanion.api.RankingRowDto
import com.tenniscompanion.integration.MatchWeighting
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

/**
 * The "fan-out" layer: pollers write here (DB durable snapshot + Redis cache), the serving API reads
 * here (Redis first, Postgres fallback). The upstream API is never touched on the read path.
 */
@Repository
class LiveDataStore(
    private val jdbc: JdbcTemplate,
    private val namedJdbc: NamedParameterJdbcTemplate,
    private val redis: StringRedisTemplate,
    private val mapper: ObjectMapper,
    private val weighting: MatchWeighting,
) {
    private val cacheTtl = Duration.ofHours(24) // sparse polling on the free tier → long TTL

    // --- live matches ---

    fun saveLiveMatches(source: String, matches: List<LiveMatchDto>) {
        matches.forEach { upsertMatch(source, it) }
        // matches no longer in the live feed are treated as finished
        val ids = matches.map { it.externalId }
        if (ids.isEmpty()) {
            jdbc.update("UPDATE live_matches SET status='finished', last_polled_at=now() WHERE source=? AND status='live'", source)
        } else {
            namedJdbc.update(
                "UPDATE live_matches SET status='finished', last_polled_at=now() WHERE source=:src AND status='live' AND external_id NOT IN (:ids)",
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
            INSERT INTO live_matches(source, external_id, status, round, surface, tour, category, tournament_name,
                                     player1_name, player2_name, player1_id, player2_id, score, start_time, last_polled_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?::bigint,?::bigint,?::jsonb,?, now())
            ON CONFLICT (source, external_id) DO UPDATE SET
              status=EXCLUDED.status, round=EXCLUDED.round, surface=EXCLUDED.surface, tour=EXCLUDED.tour,
              category=EXCLUDED.category, tournament_name=EXCLUDED.tournament_name,
              player1_name=EXCLUDED.player1_name, player2_name=EXCLUDED.player2_name,
              player1_id=EXCLUDED.player1_id, player2_id=EXCLUDED.player2_id, score=EXCLUDED.score,
              start_time=EXCLUDED.start_time, last_polled_at=now()
            """.trimIndent(),
            source, m.externalId, m.status, m.round, m.surface, m.tour, m.category, m.tournamentName,
            m.player1.name, m.player2.name, m.player1.playerId, m.player2.playerId,
            m.score?.let { mapper.writeValueAsString(it) }, m.startTime?.atOffset(ZoneOffset.UTC),
        )
    }

    fun liveMatches(source: String): List<LiveMatchDto> = weightedSort(
        redis.opsForValue().get(LIVE_KEY)?.let { mapper.readValue(it, Array<LiveMatchDto>::class.java).toList() }
            ?: readMatches(source, "live", null),
    )

    /** Completed matches from today (UTC). Cache first (fixtures-backed), else finished rows polled today. */
    fun recentMatches(source: String): List<LiveMatchDto> = weightedSort(
        redis.opsForValue().get(RECENT_KEY)?.let { mapper.readValue(it, Array<LiveMatchDto>::class.java).toList() }
            ?: readMatches(source, "finished", LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC)),
    )

    /** Order by importance (Grand Slam > tour > Challenger > …, late rounds first), then recency — so the
     *  most significant match leads regardless of when it started. Applied on read so it's consistent
     *  whether served from Redis or the Postgres fallback. */
    private fun weightedSort(matches: List<LiveMatchDto>): List<LiveMatchDto> {
        val ranks = rankByPlayerId() // current ATP/WTA ranks for the UI (the live feed doesn't carry them)
        return matches
            .map {
                it.copy(
                    tier = weighting.tierOf(it.tournamentName, it.category).name,
                    player1 = it.player1.copy(rank = it.player1.rank ?: it.player1.playerId?.let { id -> ranks[id] }),
                    player2 = it.player2.copy(rank = it.player2.rank ?: it.player2.playerId?.let { id -> ranks[id] }),
                )
            }
            .sortedWith(
                compareByDescending<LiveMatchDto> { weighting.weight(it.tournamentName, it.category, it.round) }
                    .thenByDescending { it.startTime ?: Instant.MIN },
            )
    }

    /** player_id → current rank, from the latest cached ATP/WTA rankings (namespaced ids don't collide). */
    private fun rankByPlayerId(): Map<Long, Int> {
        val map = HashMap<Long, Int>()
        for (tour in listOf("ATP", "WTA")) {
            for (r in rankings(tour, 500)) r.playerId?.let { map[it] = r.rank }
        }
        return map
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

    private fun countryByIds(ids: List<Long>): Map<Long, String> =
        namedJdbc.query(
            "SELECT player_id, country_code FROM players WHERE player_id IN (:ids) AND country_code IS NOT NULL",
            MapSqlParameterSource("ids", ids),
        ) { rs, _ -> rs.getLong("player_id") to rs.getString("country_code") }.toMap()

    private fun lastPolledAt(source: String, externalId: String): Instant? =
        jdbc.query(
            "SELECT last_polled_at FROM live_matches WHERE source = ? AND external_id = ?",
            { rs, _ -> rs.getTimestamp("last_polled_at")?.toInstant() },
            source, externalId,
        ).firstOrNull()

    private fun readMatches(source: String, status: String, since: Instant?): List<LiveMatchDto> {
        val sql = StringBuilder(
            """
            SELECT external_id, status, tournament_name, round, surface, tour, category,
                   player1_name, player2_name, player1_id, player2_id, score::text AS score_json, start_time
            FROM live_matches WHERE source = ? AND status = ?
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
                player1 = PlayerSideDto(rs.getString("player1_name"), rs.getObject("player1_id") as? Long, null, null),
                player2 = PlayerSideDto(rs.getString("player2_name"), rs.getObject("player2_id") as? Long, null, null),
                score = rs.getString("score_json")?.let { mapper.readValue(it, Map::class.java) as Map<String, Any?> },
                startTime = rs.getTimestamp("start_time")?.toInstant(),
            )
        }, *args.toTypedArray())
    }

    // --- rankings ---

    fun saveRankings(tour: String, rows: List<RankingRowDto>, capturedAt: Instant) {
        val ts = OffsetDateTime.ofInstant(capturedAt, ZoneOffset.UTC)
        for (r in rows) {
            jdbc.update(
                """
                INSERT INTO live_rankings(tour, rank, player_id, external_name, points, captured_at)
                VALUES (?,?,?::bigint,?,?,?)
                ON CONFLICT (tour, rank, captured_at) DO UPDATE SET
                  player_id=EXCLUDED.player_id, external_name=EXCLUDED.external_name, points=EXCLUDED.points
                """.trimIndent(),
                tour, r.rank, r.playerId, r.name, r.points, ts,
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
            SELECT rank, player_id, external_name, points FROM live_rankings
            WHERE tour = ? AND captured_at = (SELECT max(captured_at) FROM live_rankings WHERE tour = ?)
            ORDER BY rank LIMIT ?
            """.trimIndent(),
            { rs, _ -> RankingRowDto(rs.getInt("rank"), rs.getObject("player_id") as? Long, rs.getString("external_name"), null, (rs.getObject("points") as? Number)?.toInt()) },
            tour, tour, limit,
        )
    }

    private fun rankKey(tour: String) = "rankings:${tour.lowercase()}"

    companion object {
        private const val LIVE_KEY = "scores:live"
        private const val RECENT_KEY = "scores:recent"
    }
}
