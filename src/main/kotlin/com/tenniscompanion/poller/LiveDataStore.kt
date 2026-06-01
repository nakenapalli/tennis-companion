package com.tenniscompanion.poller

import com.tenniscompanion.api.LiveMatchDto
import com.tenniscompanion.api.PlayerSideDto
import com.tenniscompanion.api.RankingRowDto
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant
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
) {
    private val cacheTtl = Duration.ofHours(24) // sparse polling on the free tier → long TTL

    // --- live matches ---

    fun saveLiveMatches(source: String, matches: List<LiveMatchDto>) {
        for (m in matches) {
            jdbc.update(
                """
                INSERT INTO live_matches(source, external_id, status, round, surface, tour, tournament_name,
                                         player1_name, player2_name, player1_id, player2_id, score, start_time, last_polled_at)
                VALUES (?,?,?,?,?,?,?,?,?,?::bigint,?::bigint,?::jsonb,?, now())
                ON CONFLICT (source, external_id) DO UPDATE SET
                  status=EXCLUDED.status, round=EXCLUDED.round, surface=EXCLUDED.surface, tour=EXCLUDED.tour,
                  tournament_name=EXCLUDED.tournament_name, player1_name=EXCLUDED.player1_name, player2_name=EXCLUDED.player2_name,
                  player1_id=EXCLUDED.player1_id, player2_id=EXCLUDED.player2_id, score=EXCLUDED.score,
                  start_time=EXCLUDED.start_time, last_polled_at=now()
                """.trimIndent(),
                source, m.externalId, m.status, m.round, m.surface, m.tour, m.tournamentName,
                m.player1.name, m.player2.name, m.player1.playerId, m.player2.playerId,
                m.score?.let { mapper.writeValueAsString(it) }, m.startTime?.atOffset(ZoneOffset.UTC),
            )
        }
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

    fun liveMatches(source: String): List<LiveMatchDto> =
        redis.opsForValue().get(LIVE_KEY)?.let { mapper.readValue(it, Array<LiveMatchDto>::class.java).toList() }
            ?: readMatches(source, "live", null)

    fun recentMatches(source: String, since: Instant): List<LiveMatchDto> =
        readMatches(source, "finished", since)

    private fun readMatches(source: String, status: String, since: Instant?): List<LiveMatchDto> {
        val sql = StringBuilder(
            """
            SELECT external_id, status, tournament_name, round, surface, tour,
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
    }
}
