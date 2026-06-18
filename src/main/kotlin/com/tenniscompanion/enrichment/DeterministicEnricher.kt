package com.tenniscompanion.enrichment

import com.tenniscompanion.integration.TournamentSurfaceRegistry
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * Resolves enrichment tasks via deterministic lookups (no network, no LLM). Currently handles
 * tournament surface via [TournamentSurfaceRegistry]. Returns true when the task is fully resolved.
 */
@Component
class DeterministicEnricher(
    private val jdbc: JdbcTemplate,
    private val surfaceRegistry: TournamentSurfaceRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun enrich(task: EnrichmentTask): Boolean = when (task.entityType) {
        "tournament" -> task.entityId.toLongOrNull()?.let { enrichTournamentSurface(it) } ?: false
        else -> false
    }

    private fun enrichTournamentSurface(tournamentId: Long): Boolean {
        val name = jdbc.query(
            "SELECT name FROM tournaments WHERE id = ?",
            { rs, _ -> rs.getString("name") },
            tournamentId,
        ).firstOrNull() ?: return false

        val surface = surfaceRegistry.surfaceOf(name) ?: return false

        val rows = jdbc.update(
            "UPDATE tournaments SET surface = ? WHERE id = ? AND surface IS NULL",
            surface, tournamentId,
        )
        if (rows > 0) {
            val backfilled = jdbc.update(
                "UPDATE matches SET surface = ? WHERE tourney_name = ? AND surface IS NULL AND source = 'api-tennis'",
                surface, name,
            )
            log.info("Surface enriched: tournament={} surface={} matchBackfill={}", name, surface, backfilled)
        }
        return true
    }
}
