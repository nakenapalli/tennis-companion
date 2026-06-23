package com.tenniscompanion.integration

import org.springframework.stereotype.Component

/**
 * Single precedence point for resolving a tournament's surface, shared by the tournament sync (fill at
 * write time) and the enrichment queue (fill missing later). The curated [TournamentSurfaceRegistry]
 * wins — it's small, hand-verified, and lets us override a vendor mistake — falling back to the broad
 * upstream [UpstreamSurfaceCatalog] (exact match by `external_id`, covers the Challenger/ITF long tail).
 * Null when neither knows it (left for the Phase-3 LLM pass).
 */
@Component
class SurfaceResolver(
    private val registry: TournamentSurfaceRegistry,
    private val catalog: UpstreamSurfaceCatalog,
) {
    fun resolve(name: String?, externalId: String?): String? =
        registry.surfaceOf(name) ?: catalog.surfaceOf(externalId)
}
