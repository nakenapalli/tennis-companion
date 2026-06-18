package com.tenniscompanion.poller

import com.tenniscompanion.config.PollProperties
import com.tenniscompanion.enrichment.EnrichmentQueueStore
import com.tenniscompanion.integration.TennisApiAdapter
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Syncs current tournaments (derived from the live-events feed, so one upstream call). Scheduled run
 * gated by `app.poll.enabled`; also invoked on demand via the admin trigger. Draws/seeds are deferred.
 */
@Component
class TournamentSyncJob(
    private val adapter: TennisApiAdapter,
    private val store: TournamentStore,
    private val props: PollProperties,
    private val enrichmentQueue: EnrichmentQueueStore,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${POLL_TOURNAMENTS_CRON:0 30 6 * * *}")
    fun scheduled() {
        if (props.enabled) sync()
    }

    fun sync(): Int {
        val tournaments = adapter.fetchCurrentTournaments()
        store.upsert(adapter.source, tournaments)
        // Queue surface enrichment for any tournament the feed didn't supply a surface for
        store.current(adapter.source)
            .filter { it.surface.isNullOrBlank() }
            .forEach { t -> enrichmentQueue.enqueue("tournament", t.id.toString(), listOf("surface")) }
        log.info("Tournament sync: {} current tournaments", tournaments.size)
        return tournaments.size
    }
}
