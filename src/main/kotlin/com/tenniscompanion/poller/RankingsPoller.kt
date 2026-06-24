package com.tenniscompanion.poller

import com.tenniscompanion.api.RankingRowDto
import com.tenniscompanion.config.PollProperties
import com.tenniscompanion.integration.TennisApiAdapter
import com.tenniscompanion.reconcile.ReconciliationRequest
import com.tenniscompanion.reconcile.ReconciliationService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Pulls the current ATP + WTA singles rankings daily (default 06:00 UTC), reconciles each row to a
 * canonical player UUID, and upserts into the unified `rankings` table (`source='api-tennis'`) + the
 * Redis snapshot the API serves. Reconciliation never blocks the write — an unmapped row is still stored
 * with a null `player_id` and the upstream display name, then resolved later. Also runnable on demand via
 * the admin trigger and once on boot via [StartupDataSync].
 */
@Component
class RankingsPoller(
    private val adapter: TennisApiAdapter,
    private val reconciliation: ReconciliationService,
    private val store: LiveDataStore,
    private val props: PollProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${POLL_RANKINGS_CRON:0 0 6 * * *}")
    fun scheduled() {
        if (!props.enabled) return
        // Resilience: swallow upstream failures here so a bad refresh keeps the last-good snapshot rather
        // than crashing the schedule. The on-demand admin trigger calls poll() directly and DOES surface errors.
        runCatching { poll() }.onFailure { log.warn("Rankings poll skipped (upstream error): {}", it.message) }
    }

    /** Returns rows persisted per tour. Two upstream calls (ATP + WTA). */
    fun poll(): Map<String, Int> {
        val capturedAt = Instant.now()
        val result = mutableMapOf<String, Int>()
        for (tour in listOf("ATP", "WTA")) {
            val rows = adapter.fetchRankings(tour).take(props.rankingTopN).map { r ->
                val playerId = reconciliation.resolve(
                    ReconciliationRequest(
                        source = adapter.source,
                        externalId = r.player.externalId,
                        externalName = r.player.name,
                        tour = tour,
                        countryCode = r.player.countryCode,
                        rankHint = r.player.rankHint,
                    ),
                ).playerId
                RankingRowDto(rank = r.rank, playerId = playerId, name = r.player.name, country = r.player.countryCode, points = r.points)
            }
            store.saveRankings(tour, rows, capturedAt)
            val mapped = rows.count { it.playerId != null }
            log.info("Rankings poll {}: {} rows ({} reconciled)", tour, rows.size, mapped)
            result[tour] = rows.size
        }
        return result
    }
}
