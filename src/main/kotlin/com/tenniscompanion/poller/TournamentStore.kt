package com.tenniscompanion.poller

import com.tenniscompanion.api.TournamentView
import com.tenniscompanion.integration.NormalizedTournament
import com.tenniscompanion.integration.TournamentTierRegistry
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.LocalDate

@Repository
class TournamentStore(
    private val jdbc: JdbcTemplate,
    private val namedJdbc: NamedParameterJdbcTemplate,
    private val redis: StringRedisTemplate,
    private val mapper: ObjectMapper,
    private val tiers: TournamentTierRegistry,
) {
    private val cacheTtl = Duration.ofHours(24)

    /**
     * Replace this source's tournaments with the freshly synced set (delete rows no longer present —
     * e.g. ended events or old per-draw keys now collapsed by name), upsert, then refresh the cache.
     */
    fun upsert(source: String, tournaments: List<NormalizedTournament>) {
        val ids = tournaments.map { it.externalId }
        if (ids.isEmpty()) {
            jdbc.update("DELETE FROM tournaments WHERE source = ?", source)
        } else {
            namedJdbc.update(
                "DELETE FROM tournaments WHERE source = :src AND external_id NOT IN (:ids)",
                MapSqlParameterSource().addValue("src", source).addValue("ids", ids),
            )
        }
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

    fun current(source: String): List<TournamentView> = sortByTier(
        redis.opsForValue().get(CURRENT_KEY)?.let { mapper.readValue(it, Array<TournamentView>::class.java).toList() }
            ?: readCurrent(source),
    )

    /** Most important first (Grand Slam > Masters/1000 > 500 > 250 > Challenger > ITF), then soonest. */
    private fun sortByTier(list: List<TournamentView>): List<TournamentView> =
        list.sortedWith(
            compareByDescending<TournamentView> { tiers.tierOf(it.name, it.level).weight }
                .thenByDescending { it.startDate ?: LocalDate.MIN }
                .thenBy { it.name },
        )

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
