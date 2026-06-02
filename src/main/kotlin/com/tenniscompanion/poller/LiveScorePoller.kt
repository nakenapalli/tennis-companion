package com.tenniscompanion.poller

import com.tenniscompanion.config.PollProperties
import com.tenniscompanion.config.TennisApiProperties
import com.tenniscompanion.integration.TennisApiAdapter
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Pulls live matches, resolves each player to a canonical id (via [LiveMatchMapper]), and writes the
 * durable snapshot + Redis cache. Scheduled polling (default ON) runs at `app.poll.live-interval`
 * (default 60s) and only when a feed key is configured; `poll()` is also invoked on demand via the
 * admin trigger regardless of the schedule.
 */
@Component
class LiveScorePoller(
    private val adapter: TennisApiAdapter,
    private val mapper: LiveMatchMapper,
    private val store: LiveDataStore,
    private val props: PollProperties,
    private val feed: TennisApiProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${app.poll.live-interval:PT1M}")
    fun scheduled() {
        if (props.enabled && feed.key.isNotBlank()) poll()
    }

    fun poll(): Int {
        val dtos = adapter.fetchLiveMatches().map { mapper.toDto(adapter.source, it) }
        store.saveLiveMatches(adapter.source, dtos)
        val mapped = dtos.count { it.player1.playerId != null && it.player2.playerId != null }
        log.info("Live poll: {} matches ({} fully reconciled)", dtos.size, mapped)
        return dtos.size
    }
}
