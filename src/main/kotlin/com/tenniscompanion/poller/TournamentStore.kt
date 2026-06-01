package com.tenniscompanion.poller

import com.tenniscompanion.api.TournamentView
import com.tenniscompanion.integration.NormalizedTournament
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.time.Duration

@Repository
class TournamentStore(
    private val jdbc: JdbcTemplate,
    private val redis: StringRedisTemplate,
    private val mapper: ObjectMapper,
) {
    private val cacheTtl = Duration.ofHours(24)

    /** Upsert the synced tournaments, then refresh the `tournaments:current` cache from the DB. */
    fun upsert(source: String, tournaments: List<NormalizedTournament>) {
        for (t in tournaments) {
            jdbc.update(
                """
                INSERT INTO tournaments(source, external_id, name, level, surface, location, tour, start_date, end_date)
                VALUES (?,?,?,?,?,?,?,?,?)
                ON CONFLICT (source, external_id) DO UPDATE SET
                  name=EXCLUDED.name, level=EXCLUDED.level, surface=EXCLUDED.surface,
                  location=EXCLUDED.location, tour=EXCLUDED.tour, start_date=EXCLUDED.start_date, end_date=EXCLUDED.end_date
                """.trimIndent(),
                source, t.externalId, t.name, t.level, t.surface, t.location, t.tour, t.startDate, t.endDate,
            )
        }
        redis.opsForValue().set(CURRENT_KEY, mapper.writeValueAsString(readCurrent(source)), cacheTtl)
    }

    fun current(source: String): List<TournamentView> =
        redis.opsForValue().get(CURRENT_KEY)?.let { mapper.readValue(it, Array<TournamentView>::class.java).toList() }
            ?: readCurrent(source)

    fun byId(id: Long): TournamentView? =
        jdbc.query("SELECT * FROM tournaments WHERE id = ?", ROW_MAPPER, id).firstOrNull()

    /** Current/upcoming = not yet ended (end_date null or recent). */
    private fun readCurrent(source: String): List<TournamentView> =
        jdbc.query(
            "SELECT * FROM tournaments WHERE source = ? AND (end_date IS NULL OR end_date >= current_date - 1) ORDER BY start_date NULLS LAST, name",
            ROW_MAPPER, source,
        )

    companion object {
        private const val CURRENT_KEY = "tournaments:current"

        private val ROW_MAPPER = RowMapper { rs, _ ->
            TournamentView(
                id = rs.getLong("id"),
                externalId = rs.getString("external_id"),
                name = rs.getString("name"),
                level = rs.getString("level"),
                surface = rs.getString("surface"),
                location = rs.getString("location"),
                tour = rs.getString("tour"),
                startDate = rs.getDate("start_date")?.toLocalDate(),
                endDate = rs.getDate("end_date")?.toLocalDate(),
            )
        }
    }
}
