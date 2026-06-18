package com.tenniscompanion.integration

import com.tenniscompanion.reconcile.NameNormalizer
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue

/**
 * Resolves a tournament name to its playing surface using a curated JSON lookup. Parallel to
 * [TournamentTierRegistry] — same accent/case/punctuation-insensitive matching via [NameNormalizer.fold].
 * Returns null for unknown tournaments (hand those to the enrichment queue for Phase 3 agent work).
 */
@Component
class TournamentSurfaceRegistry(mapper: ObjectMapper) {

    private val surfaces: Map<String, String> = load(mapper)

    fun surfaceOf(tournamentName: String?): String? =
        tournamentName?.let { surfaces[NameNormalizer.fold(it)] }

    private fun load(mapper: ObjectMapper): Map<String, String> = runCatching {
        val res = ClassPathResource("tournament-surfaces.json")
        if (!res.exists()) return emptyMap()
        res.inputStream.use { mapper.readValue<Map<String, String>>(it) }
            .filterKeys { !it.startsWith("_") }
            .mapKeys { (name, _) -> NameNormalizer.fold(name) }
    }.getOrElse {
        LoggerFactory.getLogger(javaClass).warn("Could not load tournament-surfaces.json: {}", it.message)
        emptyMap()
    }
}
