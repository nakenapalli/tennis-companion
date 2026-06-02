package com.tenniscompanion.poller

import com.tenniscompanion.config.PollProperties
import com.tenniscompanion.config.TennisApiProperties
import com.tenniscompanion.integration.TennisApiAdapter
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Syncs today's completed matches (one get_fixtures call), so the UI can show "completed today" when
 * nothing is live. Scheduled less often than the live poll (default 15m) since finished results only
 * change as matches end. Gated by `app.poll.enabled` + a configured key; also triggerable via admin.
 */
@Component
class RecentScoresJob(
    private val adapter: TennisApiAdapter,
    private val mapper: LiveMatchMapper,
    private val store: LiveDataStore,
    private val props: PollProperties,
    private val feed: TennisApiProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${app.poll.recent-interval:PT15M}")
    fun scheduled() {
        if (props.enabled && feed.key.isNotBlank()) sync()
    }

    fun sync(): Int {
        val dtos = adapter.fetchRecentMatches().map { mapper.toDto(adapter.source, it) }
        store.saveRecentMatches(adapter.source, dtos)
        log.info("Recent scores: {} completed matches today", dtos.size)
        return dtos.size
    }
}
