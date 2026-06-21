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
import java.util.UUID

/**
 * One-time historical load of the Sackmann CSVs into players / rankings / matches.
 * Runs on startup only when `app.historical-load.enabled=true`. Idempotent: every insert uses
 * ON CONFLICT DO NOTHING against the natural key, so re-running never duplicates.
 *
 * WTA sackmann_ids use a 1e9 offset so the UNIQUE(sackmann_id) constraint stays satisfied
 * (raw ATP and WTA player_ids from the CSVs can overlap). After players are loaded, a
 * sackmann_id → UUID map is built; all rankings and matches FKs are written as UUIDs.
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

        // After all players are inserted, build sackmann_id → UUID map for FK resolution.
        val uuidMap = buildUuidMap()
        log.info("UUID map built: {} players", uuidMap.size)

        for (suffix in props.rankingFiles) {
            loadRankings("ATP", File(props.atpDir, "atp_rankings_$suffix.csv"), uuidMap, 0L)
            loadRankings("WTA", File(props.wtaDir, "wta_rankings_$suffix.csv"), uuidMap, WTA_ID_OFFSET)
        }

        for (year in props.seasons) {
            loadMatches("ATP", File(props.atpDir, "atp_matches_$year.csv"), uuidMap, 0L)
            loadMatches("WTA", File(props.wtaDir, "wta_matches_$year.csv"), uuidMap, WTA_ID_OFFSET)
        }

        log.info(
            "Historical load done in {} ms — players={}, rankings={}, matches={}",
            System.currentTimeMillis() - started,
            count("players"), count("rankings"), count("matches"),
        )
    }

    private fun loadPlayers(tour: String, file: File, offset: Long) {
        val sql = """
            INSERT INTO players(sackmann_id, source_player_id, first_name, last_name, hand,
                                birth_date, country_code, height_cm, tour)
            VALUES (?,?,?,?,?,?,?,?,?)
            ON CONFLICT (sackmann_id) DO NOTHING
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

    /** Read all sackmann_id → UUID pairs after player load so rankings and matches can use UUID FKs. */
    private fun buildUuidMap(): Map<Long, UUID> =
        jdbc.query("SELECT sackmann_id, id FROM players") { rs, _ ->
            rs.getLong("sackmann_id") to rs.getObject("id", UUID::class.java)
        }.toMap()

    private fun loadRankings(tour: String, file: File, uuidMap: Map<Long, UUID>, offset: Long) {
        val sql = """
            INSERT INTO rankings(source, ranking_date, tour, rank, player_id, points)
            VALUES ('sackmann', ?,?,?,?::uuid,?)
            -- Sackmann ranks can tie; the one-row-per-player key is what's unique (see V10).
            ON CONFLICT (source, ranking_date, tour, player_id) WHERE source = 'sackmann' DO NOTHING
        """.trimIndent()
        val n = load(file, sql) { r ->
            val date = parseDate(r.opt("ranking_date")) ?: return@load null
            val pid = r.opt("player")?.toLongOrNull() ?: return@load null
            val uuid = uuidMap[pid + offset] ?: return@load null
            arrayOf(date, tour, r.opt("rank")?.toIntOrNull(), uuid.toString(), r.opt("points")?.toIntOrNull())
        }
        log.info("{} rankings: {} rows from {}", tour, n, file.name)
    }

    private fun loadMatches(tour: String, file: File, uuidMap: Map<Long, UUID>, offset: Long) {
        // Natural dedup key (tour, tourney_id, match_num) is unchanged from the pre-UUID schema.
        // In Sackmann CSVs the winner is always player 1, so player1_id = winner and player2_id = loser.
        val sql = """
            INSERT INTO matches(source, status, tour, tourney_id, tourney_name, surface, tourney_level,
                                tourney_date, match_num, round, best_of,
                                winner_id, loser_id, winner_name, loser_name, score,
                                player1_id, player2_id, player1_name, player2_name)
            VALUES ('sackmann','finished',?,?,?,?,?,?,?,?,?,?::uuid,?::uuid,?,?,?,?::uuid,?::uuid,?,?)
            ON CONFLICT (tour, tourney_id, match_num) DO NOTHING
        """.trimIndent()
        val n = load(file, sql) { r ->
            val rawWid = r.opt("winner_id")?.toLongOrNull() ?: return@load null
            val rawLid = r.opt("loser_id")?.toLongOrNull() ?: return@load null
            val winnerStr = (uuidMap[rawWid + offset] ?: return@load null).toString()
            val loserStr = (uuidMap[rawLid + offset] ?: return@load null).toString()
            val wName = r.opt("winner_name")
            val lName = r.opt("loser_name")
            arrayOf(
                tour, r.opt("tourney_id"), r.opt("tourney_name"), r.opt("surface"), r.opt("tourney_level"),
                parseDate(r.opt("tourney_date")), r.opt("match_num")?.toIntOrNull(), r.opt("round"),
                r.opt("best_of")?.toIntOrNull(),
                winnerStr, loserStr, wName, lName, r.opt("score"),
                winnerStr, loserStr, wName, lName,
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
        // Offset applied to WTA player ids to avoid collision with ATP ids under UNIQUE(sackmann_id).
        private const val WTA_ID_OFFSET = 1_000_000_000L
        private const val BATCH_SIZE = 1_000
    }
}
