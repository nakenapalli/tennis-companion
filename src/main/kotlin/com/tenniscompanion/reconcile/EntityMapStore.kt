package com.tenniscompanion.reconcile

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

data class UnmappedEntity(
    val source: String,
    val externalPlayerId: String,
    val externalName: String?,
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
            SELECT source, external_player_id, external_name, tour, country_code, rank_hint
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
                )
            },
            limit,
        )

    /** The human-review queue: everything not yet confirmed. */
    fun unmapped(limit: Int): List<UnmappedEntity> =
        jdbc.query(
            "SELECT source, external_player_id, external_name, confidence, tier, rationale FROM entity_map WHERE confirmed = FALSE ORDER BY updated_at DESC LIMIT ?",
            { rs, _ ->
                UnmappedEntity(
                    rs.getString("source"),
                    rs.getString("external_player_id"),
                    rs.getString("external_name"),
                    (rs.getObject("confidence") as? Number)?.toDouble(),
                    rs.getString("tier"),
                    rs.getString("rationale"),
                )
            },
            limit,
        )

    /** Admin confirms a mapping from the review endpoint. */
    fun confirm(source: String, externalId: String, playerId: UUID) {
        jdbc.update(
            "UPDATE entity_map SET player_id = ?, confirmed = TRUE, tier = 'MANUAL', confidence = 1.0, rationale = 'Human-confirmed', updated_at = now() WHERE source = ? AND external_player_id = ?",
            playerId, source, externalId,
        )
    }

    /** Tier-3 post-pick: resolve a sackmann_id to the canonical UUID for writing to entity_map. */
    fun playerUuidBySackmannId(sackmannId: Long): UUID? =
        jdbc.query(
            "SELECT id FROM players WHERE sackmann_id = ?",
            { rs, _ -> rs.getObject("id", UUID::class.java) },
            sackmannId,
        ).firstOrNull()
}
