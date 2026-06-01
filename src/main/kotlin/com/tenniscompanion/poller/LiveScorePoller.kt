package com.tenniscompanion.poller

import com.tenniscompanion.api.LiveMatchDto
import com.tenniscompanion.api.PlayerSideDto
import com.tenniscompanion.config.PollProperties
import com.tenniscompanion.integration.NormalizedPlayerRef
import com.tenniscompanion.integration.TennisApiAdapter
import com.tenniscompanion.reconcile.ReconciliationRequest
import com.tenniscompanion.reconcile.ReconciliationService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Pulls live matches, resolves each player to a canonical id via the reconciliation engine, and
 * writes the durable snapshot + Redis cache. Scheduled polling is gated by `app.poll.enabled`
 * (off by default — free tier); `poll()` is also invoked on demand via the admin trigger.
 */
@Component
class LiveScorePoller(
    private val adapter: TennisApiAdapter,
    private val reconciliation: ReconciliationService,
    private val store: LiveDataStore,
    private val props: PollProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "PT5M")
    fun scheduled() {
        if (props.enabled) poll()
    }

    fun poll(): Int {
        val dtos = adapter.fetchLiveMatches().map { m ->
            LiveMatchDto(
                externalId = m.externalId,
                status = m.status,
                tournamentName = m.tournamentName,
                round = m.round,
                surface = m.surface,
                tour = m.tour,
                player1 = side(m.player1),
                player2 = side(m.player2),
                score = m.score,
                startTime = m.startTime,
            )
        }
        store.saveLiveMatches(adapter.source, dtos)
        val mapped = dtos.count { it.player1.playerId != null && it.player2.playerId != null }
        log.info("Live poll: {} matches ({} fully reconciled)", dtos.size, mapped)
        return dtos.size
    }

    private fun side(p: NormalizedPlayerRef): PlayerSideDto {
        val playerId = reconciliation.resolve(
            ReconciliationRequest(
                source = adapter.source,
                externalId = p.externalId,
                externalName = p.name,
                tour = p.tour,
                countryCode = p.countryCode,
                rankHint = p.rankHint,
            ),
        ).playerId
        return PlayerSideDto(name = p.name, playerId = playerId, country = p.countryCode, rank = p.rankHint)
    }
}
