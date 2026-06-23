package com.tenniscompanion.poller

import com.tenniscompanion.config.PollProperties
import com.tenniscompanion.enrichment.EnrichmentQueueStore
import com.tenniscompanion.integration.SurfaceResolver
import com.tenniscompanion.integration.TennisApiAdapter
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Syncs current tournaments (derived from the live-events feed, so one upstream call). Scheduled run
 * gated by `app.poll.enabled`; also invoked on demand via the admin trigger. Draws/seeds are deferred.
 * Surface (absent from the fixtures feed) is filled at write time via [SurfaceResolver]; whatever it
 * still can't resolve is queued for the enrichment job to retry.
 */
@Component
class TournamentSyncJob(
    private val adapter: TennisApiAdapter,
    private val store: TournamentStore,
    private val props: PollProperties,
    private val enrichmentQueue: EnrichmentQueueStore,
    private val surfaceResolver: SurfaceResolver,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${POLL_TOURNAMENTS_CRON:0 30 6 * * *}")
    fun scheduled() {
        if (!props.enabled) return
        runCatching { sync() }.onFailure { log.warn("Tournament sync skipped (upstream error): {}", it.message) }
    }

    fun sync(): Int {
        val tournaments = adapter.fetchCurrentTournaments().map { t ->
            if (t.surface.isNullOrBlank()) t.copy(surface = surfaceResolver.resolve(t.name, t.externalId)) else t
        }
        store.upsert(adapter.source, tournaments)
        // Queue surface enrichment for any tournament still missing a surface after the write-time fill
        store.current(adapter.source)
            .filter { it.surface.isNullOrBlank() }
            .forEach { t -> enrichmentQueue.enqueue("tournament", t.id.toString(), listOf("surface")) }
        val filled = tournaments.count { !it.surface.isNullOrBlank() }
        log.info("Tournament sync: {} current tournaments ({} with surface)", tournaments.size, filled)
        return tournaments.size
    }
}
