package com.tenniscompanion.loader

import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVRecord
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.io.File
import java.sql.PreparedStatement
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * One-time historical load of the Sackmann CSVs into players / rankings_history / matches.
 * Runs on startup only when `app.historical-load.enabled=true`. Idempotent: every insert uses
 * ON CONFLICT DO NOTHING against a natural key, so re-running never duplicates.
 *
 * WTA ids are offset by 1e9 so the canonical `player_id` stays globally unique (ATP/WTA ids collide).
 */
@Component
class HistoricalDataLoader(
    private val props: HistoricalLoadProperties,
    private val jdbc: JdbcTemplate,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        if (!props.enabled) {
            log.info("Historical load disabled (set app.historical-load.enabled=true to run it).")
            return
        }
        val started = System.currentTimeMillis()
        log.info("Historical load starting — seasons={}, rankingFiles={}", props.seasons, props.rankingFiles)

        loadPlayers("ATP", File(props.atpDir, "atp_players.csv"), 0L)
        loadPlayers("WTA", File(props.wtaDir, "wta_players.csv"), WTA_ID_OFFSET)

        for (suffix in props.rankingFiles) {
            loadRankings("ATP", File(props.atpDir, "atp_rankings_$suffix.csv"), 0L)
            loadRankings("WTA", File(props.wtaDir, "wta_rankings_$suffix.csv"), WTA_ID_OFFSET)
        }

        for (year in props.seasons) {
            loadMatches("ATP", File(props.atpDir, "atp_matches_$year.csv"), 0L)
            loadMatches("WTA", File(props.wtaDir, "wta_matches_$year.csv"), WTA_ID_OFFSET)
        }

        log.info(
            "Historical load done in {} ms — players={}, rankings={}, matches={}",
            System.currentTimeMillis() - started,
            count("players"), count("rankings_history"), count("matches"),
        )
    }

    private fun loadPlayers(tour: String, file: File, offset: Long) {
        val sql = """
            INSERT INTO players(player_id, source_player_id, first_name, last_name, hand,
                                birth_date, country_code, height_cm, tour)
            VALUES (?,?,?,?,?,?,?,?,?)
            ON CONFLICT (player_id) DO NOTHING
        """.trimIndent()
        val n = load(file, sql) { r ->
            val raw = r.opt("player_id")?.toLongOrNull() ?: return@load null
            arrayOf(
                raw + offset, raw, r.opt("name_first"), r.opt("name_last"), r.opt("hand")?.take(1),
                parseDate(r.opt("dob")), r.opt("ioc"), r.opt("height")?.toIntOrNull(), tour,
            )
        }
        log.info("{} players: {} rows from {}", tour, n, file.name)
    }

    private fun loadRankings(tour: String, file: File, offset: Long) {
        val sql = """
            INSERT INTO rankings_history(ranking_date, player_id, rank, points, tour)
            VALUES (?,?,?,?,?)
            ON CONFLICT (ranking_date, player_id, tour) DO NOTHING
        """.trimIndent()
        val n = load(file, sql) { r ->
            val date = parseDate(r.opt("ranking_date")) ?: return@load null
            val pid = r.opt("player")?.toLongOrNull() ?: return@load null
            arrayOf(date, pid + offset, r.opt("rank")?.toIntOrNull(), r.opt("points")?.toIntOrNull(), tour)
        }
        log.info("{} rankings: {} rows from {}", tour, n, file.name)
    }

    private fun loadMatches(tour: String, file: File, offset: Long) {
        val sql = """
            INSERT INTO matches(tour, tourney_id, tourney_name, surface, tourney_level, tourney_date,
                                match_num, round, best_of, winner_id, loser_id, winner_name, loser_name, score)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT (tour, tourney_id, match_num) DO NOTHING
        """.trimIndent()
        val n = load(file, sql) { r ->
            arrayOf(
                tour, r.opt("tourney_id"), r.opt("tourney_name"), r.opt("surface"), r.opt("tourney_level"),
                parseDate(r.opt("tourney_date")), r.opt("match_num")?.toIntOrNull(), r.opt("round"),
                r.opt("best_of")?.toIntOrNull(),
                r.opt("winner_id")?.toLongOrNull()?.plus(offset),
                r.opt("loser_id")?.toLongOrNull()?.plus(offset),
                r.opt("winner_name"), r.opt("loser_name"), r.opt("score"),
            )
        }
        log.info("{} matches: {} rows from {}", tour, n, file.name)
    }

    // --- helpers ---

    /** Stream a CSV, map each record to a row (null = skip), and batch-insert. Returns rows processed. */
    private fun load(file: File, sql: String, map: (CSVRecord) -> Array<Any?>?): Int {
        if (!file.exists()) {
            log.warn("Missing {}, skipping", file.path)
            return 0
        }
        val format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).setTrim(true).build()
        val batch = ArrayList<Array<Any?>>(BATCH_SIZE)
        var total = 0
        file.bufferedReader().use { reader ->
            format.parse(reader).use { parser ->
                for (record in parser) {
                    val row = runCatching { map(record) }.getOrNull() ?: continue
                    batch.add(row)
                    if (batch.size >= BATCH_SIZE) { flush(sql, batch); total += batch.size; batch.clear() }
                }
            }
        }
        if (batch.isNotEmpty()) { flush(sql, batch); total += batch.size }
        return total
    }

    private fun flush(sql: String, batch: List<Array<Any?>>) {
        jdbc.batchUpdate(sql, object : BatchPreparedStatementSetter {
            override fun getBatchSize() = batch.size
            override fun setValues(ps: PreparedStatement, i: Int) {
                val row = batch[i]
                // setObject handles nulls and java.time.LocalDate cleanly on the Postgres driver.
                for (col in row.indices) ps.setObject(col + 1, row[col])
            }
        })
    }

    private fun count(table: String): Long = jdbc.queryForObject("SELECT count(*) FROM $table", Long::class.java) ?: 0

    /** Value of a column, or null if absent/blank. */
    private fun CSVRecord.opt(name: String): String? =
        if (isMapped(name)) get(name).trim().ifBlank { null } else null

    /** Sackmann dates are YYYYMMDD; reject blanks and 00 month/day (e.g. 19960000). */
    private fun parseDate(value: String?): LocalDate? {
        val v = value?.trim() ?: return null
        if (v.length != 8 || v.substring(4, 6) == "00" || v.substring(6, 8) == "00") return null
        return runCatching { LocalDate.parse(v, DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull()
    }

    companion object {
        private const val WTA_ID_OFFSET = 1_000_000_000L
        private const val BATCH_SIZE = 1_000
    }
}
