package com.tenniscompanion.api

import com.tenniscompanion.poller.LiveScorePoller
import com.tenniscompanion.poller.RankingsPoller
import com.tenniscompanion.poller.TournamentSyncJob
import com.tenniscompanion.reconcile.EntityMapStore
import com.tenniscompanion.reconcile.UnmappedEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class ConfirmMappingRequest(val source: String, val externalPlayerId: String, val playerId: Long)

/**
 * Admin: reconciliation review queue + on-demand poll triggers (the free tier is ~50 req/day, so we
 * poll deliberately rather than on a cron). NOTE: open for now — gated behind an admin role in Phase 4.
 */
@RestController
@RequestMapping("/api/admin")
class AdminController(
    private val store: EntityMapStore,
    private val liveScorePoller: LiveScorePoller,
    private val rankingsPoller: RankingsPoller,
    private val tournamentSyncJob: TournamentSyncJob,
) {

    @GetMapping("/unmapped-entities")
    fun unmapped(@RequestParam(defaultValue = "100") limit: Int): List<UnmappedEntity> =
        store.unmapped(limit.coerceIn(1, 500))

    @PostMapping("/entity-map")
    fun confirm(@RequestBody req: ConfirmMappingRequest): Map<String, Any> {
        store.confirm(req.source, req.externalPlayerId, req.playerId)
        return mapOf("status" to "confirmed", "source" to req.source, "playerId" to req.playerId)
    }

    @PostMapping("/poll/live")
    fun pollLive(): Map<String, Any> = mapOf("polledMatches" to liveScorePoller.poll())

    @PostMapping("/poll/rankings")
    fun pollRankings(): Map<String, Any> = mapOf("polledRankings" to rankingsPoller.poll())

    @PostMapping("/poll/tournaments")
    fun pollTournaments(): Map<String, Any> = mapOf("syncedTournaments" to tournamentSyncJob.sync())
}
