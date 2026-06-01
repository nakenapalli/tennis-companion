package com.tenniscompanion.reconcile

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

data class UnmappedEntity(
    val source: String,
    val externalPlayerId: String,
    val externalName: String?,
    val confidence: Double?,
    val tier: String?,
    val rationale: String?,
)

/** Reads/writes `entity_map`. Plain JDBC — the composite key makes JPA more ceremony than it's worth. */
@Repository
class EntityMapStore(private val jdbc: JdbcTemplate) {

    /** Tier 0: a confirmed mapping resolves instantly (this is what makes recon cheaper over time). */
    fun findConfirmed(source: String, externalId: String): Long? =
        jdbc.query(
            "SELECT player_id FROM entity_map WHERE source = ? AND external_player_id = ? AND confirmed = TRUE AND player_id IS NOT NULL",
            { rs, _ -> rs.getLong("player_id") },
            source, externalId,
        ).firstOrNull()

    fun save(
        source: String,
        externalId: String,
        externalName: String?,
        playerId: Long?,
        confidence: Double?,
        confirmed: Boolean,
        tier: String,
        rationale: String,
    ) {
        jdbc.update(
            """
            INSERT INTO entity_map(source, external_player_id, external_name, player_id, confidence, confirmed, tier, rationale, updated_at)
            VALUES (?,?,?,?::bigint,?::real,?,?,?, now())
            ON CONFLICT (source, external_player_id) DO UPDATE SET
              external_name = EXCLUDED.external_name,
              player_id     = EXCLUDED.player_id,
              confidence    = EXCLUDED.confidence,
              confirmed     = EXCLUDED.confirmed,
              tier          = EXCLUDED.tier,
              rationale     = EXCLUDED.rationale,
              updated_at    = now()
            """.trimIndent(),
            source, externalId, externalName, playerId, confidence, confirmed, tier, rationale,
        )
    }

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
    fun confirm(source: String, externalId: String, playerId: Long) {
        jdbc.update(
            "UPDATE entity_map SET player_id = ?, confirmed = TRUE, tier = 'MANUAL', confidence = 1.0, rationale = 'Human-confirmed', updated_at = now() WHERE source = ? AND external_player_id = ?",
            playerId, source, externalId,
        )
    }
}
