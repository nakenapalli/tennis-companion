package com.tenniscompanion.reconcile

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

data class UnmappedEntity(
    val source: String,
    val externalPlayerId: String,
    val externalName: String?,
    val country: String?,  // IOC code — stored from the rankings feed or an enriched profile (else null)
    val rankHint: Int?,    // upstream rank — stored from the rankings feed or an enriched profile
    val birthYear: Int?,   // enriched from the upstream profile (get_players); null until fetched
    val confidence: Double?,
    val tier: String?,
    val rationale: String?,
)

/** An unresolved row plus the stored signals Tier 3 needs to rebuild its candidate set offline. */
data class ReconcileQueueRow(
    val source: String,
    val externalPlayerId: String,
    val externalName: String?,
    val tour: String?,
    val countryCode: String?,
    val rankHint: Int?,
    val birthYear: Int? = null,
)

/** Reads/writes `entity_map`. Plain JDBC — the composite key makes JPA more ceremony than it's worth. */
@Repository
class EntityMapStore(private val jdbc: JdbcTemplate) {

    /** Tier 0: a confirmed mapping resolves instantly (this is what makes recon cheaper over time). */
    fun findConfirmed(source: String, externalId: String): UUID? =
        jdbc.query(
            "SELECT player_id FROM entity_map WHERE source = ? AND external_player_id = ? AND confirmed = TRUE AND player_id IS NOT NULL",
            { rs, _ -> rs.getObject("player_id", UUID::class.java) },
            source, externalId,
        ).firstOrNull()

    fun save(
        source: String,
        externalId: String,
        externalName: String?,
        playerId: UUID?,
        confidence: Double?,
        confirmed: Boolean,
        tier: String,
        rationale: String,
        tour: String? = null,
        countryCode: String? = null,
        rankHint: Int? = null,
    ) {
        jdbc.update(
            """
            INSERT INTO entity_map(source, external_player_id, external_name, player_id, confidence, confirmed, tier, rationale, tour, country_code, rank_hint, updated_at)
            VALUES (?,?,?,?::uuid,?::real,?,?,?,?,?,?::int, now())
            ON CONFLICT (source, external_player_id) DO UPDATE SET
              external_name = EXCLUDED.external_name,
              player_id     = EXCLUDED.player_id,
              confidence    = EXCLUDED.confidence,
              confirmed     = EXCLUDED.confirmed,
              tier          = EXCLUDED.tier,
              rationale     = EXCLUDED.rationale,
              -- keep an existing signal if this write didn't carry one (COALESCE, not overwrite-with-null)
              tour          = COALESCE(EXCLUDED.tour, entity_map.tour),
              country_code  = COALESCE(EXCLUDED.country_code, entity_map.country_code),
              rank_hint     = COALESCE(EXCLUDED.rank_hint, entity_map.rank_hint),
              updated_at    = now()
            """.trimIndent(),
            source, externalId, externalName, playerId?.toString(), confidence, confirmed, tier, rationale, tour, countryCode, rankHint,
        )
    }

    /** Tier 3's input: the still-unresolved rows the LLM hasn't examined yet, newest first. */
    fun unresolvedForTier3(limit: Int): List<ReconcileQueueRow> =
        jdbc.query(
            """
            SELECT source, external_player_id, external_name, tour, country_code, rank_hint, birth_year
            FROM entity_map
            WHERE confirmed = FALSE AND player_id IS NULL AND tier = 'UNRESOLVED'
            ORDER BY updated_at DESC LIMIT ?
            """.trimIndent(),
            { rs, _ ->
                ReconcileQueueRow(
                    rs.getString("source"),
                    rs.getString("external_player_id"),
                    rs.getString("external_name"),
                    rs.getString("tour"),
                    rs.getString("country_code"),
                    (rs.getObject("rank_hint") as? Number)?.toInt(),
                    (rs.getObject("birth_year") as? Number)?.toInt(),
                )
            },
            limit,
        )

    /** The human-review queue: everything not yet confirmed. */
    fun unmapped(limit: Int): List<UnmappedEntity> =
        jdbc.query(
            "SELECT source, external_player_id, external_name, country_code, rank_hint, birth_year, confidence, tier, rationale FROM entity_map WHERE confirmed = FALSE ORDER BY updated_at DESC LIMIT ?",
            { rs, _ ->
                UnmappedEntity(
                    rs.getString("source"),
                    rs.getString("external_player_id"),
                    rs.getString("external_name"),
                    rs.getString("country_code"),
                    (rs.getObject("rank_hint") as? Number)?.toInt(),
                    (rs.getObject("birth_year") as? Number)?.toInt(),
                    (rs.getObject("confidence") as? Number)?.toDouble(),
                    rs.getString("tier"),
                    rs.getString("rationale"),
                )
            },
            limit,
        )

    /**
     * A single review-queue row with its stored signals — lets the admin review UI rebuild the same
     * surname candidate set the live cascade / Tier 3 would use, for one entity on demand.
     */
    fun queueRow(source: String, externalId: String): ReconcileQueueRow? =
        jdbc.query(
            """
            SELECT source, external_player_id, external_name, tour, country_code, rank_hint, birth_year
            FROM entity_map WHERE source = ? AND external_player_id = ?
            """.trimIndent(),
            { rs, _ ->
                ReconcileQueueRow(
                    rs.getString("source"),
                    rs.getString("external_player_id"),
                    rs.getString("external_name"),
                    rs.getString("tour"),
                    rs.getString("country_code"),
                    (rs.getObject("rank_hint") as? Number)?.toInt(),
                    (rs.getObject("birth_year") as? Number)?.toInt(),
                )
            },
            source, externalId,
        ).firstOrNull()

    /**
     * Persist a live-fetched upstream profile onto a still-unconfirmed queue row, so the admin card and
     * the offline Tier-3 classifier can read country/rank/birth year without re-fetching. COALESCE keeps
     * an existing value when this profile didn't carry one; `updated_at` is deliberately NOT touched so
     * enrichment doesn't reshuffle the review queue (which is ordered by recency).
     */
    fun updateProfile(source: String, externalId: String, countryCode: String?, rankHint: Int?, birthYear: Int?) {
        jdbc.update(
            """
            UPDATE entity_map SET
              country_code = COALESCE(?, country_code),
              rank_hint    = COALESCE(?::int, rank_hint),
              birth_year   = COALESCE(?::int, birth_year)
            WHERE source = ? AND external_player_id = ? AND confirmed = FALSE
            """.trimIndent(),
            countryCode, rankHint, birthYear, source, externalId,
        )
    }

    /**
     * Admin confirms a mapping from the review endpoint, then heals rows written while this player was
     * unmapped. One transaction so the mapping + back-fill commit together. Returns the number of
     * historical rows back-filled (for admin feedback).
     */
    @Transactional
    fun confirm(source: String, externalId: String, playerId: UUID): Int {
        jdbc.update(
            "UPDATE entity_map SET player_id = ?, confirmed = TRUE, tier = 'MANUAL', confidence = 1.0, rationale = 'Human-confirmed', updated_at = now() WHERE source = ? AND external_player_id = ?",
            playerId, source, externalId,
        )
        return backfill(source, externalId)
    }

    /**
     * Stamp the now-confirmed UUID onto rows written while this upstream player was unmapped
     * (`*_id IS NULL`). Forward polls already resolve via the Tier-0 cache; this fixes already-written
     * history so the player's profile, H2H, and rankings link up immediately — including older finished
     * matches that won't be re-polled. Joined to the confirmed `entity_map` row and scoped by
     * `source + tour + exact display name` (matches/rankings store the display name, not the upstream
     * key) — so the one limitation is two same-named players in the same tour. The player profile keys on
     * `winner_id`/`loser_id`; the live-score cards on `player1_id`/`player2_id`; rankings on `player_id`.
     */
    private fun backfill(source: String, externalId: String): Int {
        fun fill(column: String, nameColumn: String, table: String) = jdbc.update(
            """
            UPDATE $table t SET $column = em.player_id FROM entity_map em
            WHERE em.source = ? AND em.external_player_id = ? AND em.player_id IS NOT NULL
              AND t.source = em.source AND t.tour = em.tour
              AND t.$nameColumn = em.external_name AND t.$column IS NULL
            """.trimIndent(),
            source, externalId,
        )
        return fill("winner_id", "winner_name", "matches") +
            fill("loser_id", "loser_name", "matches") +
            fill("player1_id", "player1_name", "matches") +
            fill("player2_id", "player2_name", "matches") +
            fill("player_id", "external_name", "rankings")
    }

    /** Tier-3 post-pick: resolve a sackmann_id to the canonical UUID for writing to entity_map. */
    fun playerUuidBySackmannId(sackmannId: Long): UUID? =
        jdbc.query(
            "SELECT id FROM players WHERE sackmann_id = ?",
            { rs, _ -> rs.getObject("id", UUID::class.java) },
            sackmannId,
        ).firstOrNull()
}
