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
        if (props.enabled) poll()
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
