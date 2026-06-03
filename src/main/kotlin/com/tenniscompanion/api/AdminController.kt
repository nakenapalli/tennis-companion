package com.tenniscompanion.api

import com.tenniscompanion.insight.DigestStore
import com.tenniscompanion.insight.StoredInsight
import com.tenniscompanion.insight.WeeklyDigestJob
import com.tenniscompanion.poller.LiveScorePoller
import com.tenniscompanion.poller.RankingsPoller
import com.tenniscompanion.poller.RecentScoresJob
import com.tenniscompanion.poller.TournamentSyncJob
import com.tenniscompanion.reconcile.EntityMapStore
import com.tenniscompanion.reconcile.Tier3ReconciliationJob
import com.tenniscompanion.reconcile.Tier3Summary
import com.tenniscompanion.reconcile.UnmappedEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class ConfirmMappingRequest(val source: String, val externalPlayerId: String, val playerId: Long)

/**
 * Admin: reconciliation review queue + on-demand poll triggers (the free tier is ~50 req/day, so we
 * poll deliberately rather than on a cron). Gated to ROLE_ADMIN in SecurityConfig (the admin path).
 */
@RestController
@RequestMapping("/api/admin")
class AdminController(
    private val store: EntityMapStore,
    private val tier3Job: Tier3ReconciliationJob,
    private val liveScorePoller: LiveScorePoller,
    private val rankingsPoller: RankingsPoller,
    private val tournamentSyncJob: TournamentSyncJob,
    private val recentScoresJob: RecentScoresJob,
    private val weeklyDigestJob: WeeklyDigestJob,
    private val digestStore: DigestStore,
) {

    @GetMapping("/unmapped-entities")
    fun unmapped(@RequestParam(defaultValue = "100") limit: Int): List<UnmappedEntity> =
        store.unmapped(limit.coerceIn(1, 500))

    @PostMapping("/entity-map")
    fun confirm(@RequestBody req: ConfirmMappingRequest): Map<String, Any> {
        store.confirm(req.source, req.externalPlayerId, req.playerId)
        return mapOf("status" to "confirmed", "source" to req.source, "playerId" to req.playerId)
    }

    /** Tier-3 LLM pass over the review queue (offline batch; gated by app.llm.enabled + a key). */
    @PostMapping("/reconcile/tier3")
    fun runTier3(@RequestParam(defaultValue = "50") limit: Int): Tier3Summary =
        tier3Job.run(limit.coerceIn(1, 200))

    @PostMapping("/poll/live")
    fun pollLive(): Map<String, Any> = mapOf("polledMatches" to liveScorePoller.poll())

    @PostMapping("/poll/rankings")
    fun pollRankings(): Map<String, Any> = mapOf("polledRankings" to rankingsPoller.poll())

    @PostMapping("/poll/tournaments")
    fun pollTournaments(): Map<String, Any> = mapOf("syncedTournaments" to tournamentSyncJob.sync())

    @PostMapping("/poll/recent")
    fun pollRecent(): Map<String, Any> = mapOf("polledRecent" to recentScoresJob.sync())

    // --- AI weekly digest (generate as DRAFT, review, publish) ---

    @PostMapping("/insights/generate")
    fun generateDigest(): Map<String, Any?> = mapOf("draftId" to weeklyDigestJob.generate())

    @GetMapping("/insights")
    fun insights(@RequestParam(defaultValue = "DRAFT") status: String): List<StoredInsight> =
        digestStore.listByStatus(status.uppercase())

    @PostMapping("/insights/{id}/publish")
    fun publishInsight(@PathVariable id: Long): Map<String, Any> =
        mapOf("id" to id, "published" to digestStore.publish(id))
}
