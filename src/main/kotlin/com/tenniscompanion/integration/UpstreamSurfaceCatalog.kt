package com.tenniscompanion.integration

import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import java.time.Duration
import java.time.Instant

/**
 * Surface lookup over the upstream tournament catalog (api-tennis `get_tournaments`), keyed by
 * `tournament_key` (== a tournament's `external_id`). The catalog is ~10k near-static entries, so a
 * single upstream call is shared via two cache layers: Redis (24h, survives restarts / shared across
 * instances) over an in-process memo (avoids re-parsing the JSON map on every lookup within a batch).
 * Only entries with a known surface are kept. A failed fetch yields an empty map and is NOT cached, so
 * the next call retries — matching the pollers' "degrade, keep trying" convention.
 */
@Component
class UpstreamSurfaceCatalog(
    private val adapter: TennisApiAdapter,
    private val redis: StringRedisTemplate,
    private val mapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val ttl = Duration.ofHours(24)

    @Volatile private var memo: Map<String, String> = emptyMap()
    @Volatile private var memoLoadedAt: Instant = Instant.EPOCH

    /** Canonical surface (Hard/Clay/Grass) for an upstream tournament key, or null if unknown. */
    fun surfaceOf(externalId: String?): String? =
        if (externalId.isNullOrBlank()) null else snapshot()[externalId]

    /** The catalog as an `external_id` → canonical-surface map (surfaces only). */
    fun snapshot(): Map<String, String> {
        if (memo.isNotEmpty() && Instant.now().isBefore(memoLoadedAt.plus(ttl))) return memo

        redis.opsForValue().get(REDIS_KEY)
            ?.let { runCatching { mapper.readValue<Map<String, String>>(it) }.getOrNull() }
            ?.let { return remember(it) }

        val fetched = runCatching { adapter.fetchTournamentCatalog() }
            .onFailure { log.warn("Surface catalog fetch failed (keeping last-good): {}", it.message) }
            .getOrNull()
            ?: return memo // upstream error → reuse whatever we have, don't cache an empty map

        val map = fetched.mapNotNull { e -> e.surface?.let { e.externalId to it } }.toMap()
        if (map.isNotEmpty()) {
            redis.opsForValue().set(REDIS_KEY, mapper.writeValueAsString(map), ttl)
            log.info("Surface catalog loaded: {} tournaments with a known surface", map.size)
        }
        return remember(map)
    }

    private fun remember(map: Map<String, String>): Map<String, String> {
        if (map.isNotEmpty()) { memo = map; memoLoadedAt = Instant.now() }
        return map
    }

    private companion object {
        const val REDIS_KEY = "tournament:surface-catalog:v1"
    }
}
