package com.tenniscompanion.api

import com.tenniscompanion.config.NewsProperties
import com.tenniscompanion.enrichment.EnrichmentJob
import com.tenniscompanion.enrichment.EnrichmentSummary
import com.tenniscompanion.insight.DigestStore
import com.tenniscompanion.insight.MatchFacts
import com.tenniscompanion.insight.NewsSource
import com.tenniscompanion.insight.SiteScraper
import com.tenniscompanion.insight.StoredInsight
import com.tenniscompanion.insight.WeeklyDigestJob
import com.tenniscompanion.integration.NormalizedMatch
import com.tenniscompanion.integration.TennisApiAdapter
import com.tenniscompanion.integration.UpstreamPlayerProfile
import java.time.LocalDate
import java.time.ZoneOffset
import com.tenniscompanion.poller.LiveScorePoller
import com.tenniscompanion.poller.RankingsPoller
import com.tenniscompanion.poller.RecentScoresJob
import com.tenniscompanion.poller.TournamentSyncJob
import com.tenniscompanion.reconcile.CandidateFinder
import com.tenniscompanion.reconcile.EntityMapStore
import com.tenniscompanion.reconcile.NameNormalizer
import com.tenniscompanion.reconcile.Tier3ReconciliationJob
import com.tenniscompanion.reconcile.Tier3Summary
import com.tenniscompanion.reconcile.UnmappedEntity
import org.springframework.web.bind.annotation.GetMapping
import java.util.UUID
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class ConfirmMappingRequest(val source: String, val externalPlayerId: String, val playerId: UUID)

/** A canonical player offered as a possible mapping for an unmapped upstream entity (review UI). */
data class ReviewCandidate(
    val playerId: UUID,
    val sackmannId: Long?,
    val name: String,
    val country: String?,
    val birthYear: Int?,
)

/** One of the upstream player's recent results, shown in review to help recognize who they are. */
data class UpstreamMatchDto(
    val date: String?,        // ISO date (UTC) or null
    val tournamentName: String?,
    val round: String?,
    val opponentName: String?,
    val result: String?,      // "W" | "L" | null (couldn't determine)
    val score: String?,       // games per set, from this player's perspective ("6-3, 4-6, 7-5")
)

/**
 * Admin: reconciliation review queue + on-demand poll triggers (the free tier is ~50 req/day, so we
 * poll deliberately rather than on a cron). Gated to ROLE_ADMIN in SecurityConfig (the admin path).
 */
@RestController
@RequestMapping("/api/admin")
class AdminController(
    private val store: EntityMapStore,
    private val candidateFinder: CandidateFinder,
    private val adapter: TennisApiAdapter,
    private val tier3Job: Tier3ReconciliationJob,
    private val liveScorePoller: LiveScorePoller,
    private val rankingsPoller: RankingsPoller,
    private val tournamentSyncJob: TournamentSyncJob,
    private val recentScoresJob: RecentScoresJob,
    private val weeklyDigestJob: WeeklyDigestJob,
    private val digestStore: DigestStore,
    private val enrichmentJob: EnrichmentJob,
    private val news: NewsSource,
    private val newsProps: NewsProperties,
    private val scrapers: List<SiteScraper>,
) {

    @GetMapping("/unmapped-entities")
    fun unmapped(@RequestParam(defaultValue = "100") limit: Int): List<UnmappedEntity> =
        store.unmapped(limit.coerceIn(1, 500))

    /**
     * Candidate canonical players for one unmapped entity, for the human-review UI. Rebuilds the same
     * surname-blocked candidate set the live cascade and Tier 3 use (from the row's stored tour + name),
     * so a reviewer sees exactly what the matcher considered. Empty if the row is unknown, has no tour,
     * or its surname isn't in the historical set.
     */
    @GetMapping("/unmapped-entities/candidates")
    fun candidates(
        @RequestParam source: String,
        @RequestParam externalPlayerId: String,
    ): List<ReviewCandidate> {
        val row = store.queueRow(source, externalPlayerId) ?: return emptyList()
        val tour = row.tour ?: return emptyList()
        val tokens = NameNormalizer.tokens(row.externalName ?: "")
        if (tokens.isEmpty()) return emptyList()
        return candidateFinder.bySurname(tour, tokens).map {
            ReviewCandidate(
                playerId = it.playerId,
                sackmannId = it.sackmannId,
                name = listOfNotNull(it.firstName, it.lastName).joinToString(" ").trim(),
                country = it.countryCode,
                birthYear = it.birthYear,
            )
        }
    }

    /**
     * The upstream player's recent results, fetched live from the provider by their player key (the
     * `externalPlayerId`). Lets a reviewer cross-check each candidate against what this player has
     * actually been doing. One upstream call per lookup; an outage/empty response yields an empty list
     * (the UI just shows "no recent results") rather than failing the page.
     */
    @GetMapping("/unmapped-entities/upstream-matches")
    fun upstreamMatches(
        @RequestParam source: String,
        @RequestParam externalPlayerId: String,
        @RequestParam(defaultValue = "12") limit: Int,
    ): List<UpstreamMatchDto> {
        if (source != adapter.source) return emptyList()
        val matches = runCatching { adapter.fetchPlayerMatches(externalPlayerId) }.getOrElse { emptyList() }
        return matches.take(limit.coerceIn(1, 50)).map { toUpstreamMatch(externalPlayerId, it) }
    }

    /**
     * The upstream player's profile (country / birth year / rank), fetched live by player key — the
     * disambiguation signals the live-scores feed never supplied, so they're null in `entity_map` for
     * most queue rows. Always returns an object (empty on outage/no-profile) so the UI can render it.
     */
    @GetMapping("/unmapped-entities/upstream-profile")
    fun upstreamProfile(
        @RequestParam source: String,
        @RequestParam externalPlayerId: String,
    ): UpstreamPlayerProfile {
        if (source != adapter.source) return UpstreamPlayerProfile()
        val profile = runCatching { adapter.fetchPlayerProfile(externalPlayerId) }.getOrNull() ?: UpstreamPlayerProfile()
        // Enrich the queue row so repeat reviews + Tier 3 read these signals without re-fetching upstream.
        if (profile.country != null || profile.rank != null || profile.birthYear != null) {
            store.updateProfile(source, externalPlayerId, profile.country, profile.rank, profile.birthYear)
        }
        return profile
    }

    private fun toUpstreamMatch(playerKey: String, m: NormalizedMatch): UpstreamMatchDto {
        val mineSide = if (m.player1.externalId == playerKey) "home" else "away"
        val opponent = if (mineSide == "home") m.player2 else m.player1
        val winnerSide = MatchFacts.winnerOf(m.score)
        return UpstreamMatchDto(
            date = m.startTime?.atZone(ZoneOffset.UTC)?.toLocalDate()?.toString(),
            tournamentName = m.tournamentName,
            round = MatchFacts.cleanRound(m.round) ?: m.round,
            opponentName = opponent.name.ifBlank { null },
            result = winnerSide?.let { if (it == mineSide) "W" else "L" },
            score = MatchFacts.scoreFrom(m.score, mineSide).ifBlank { null },
        )
    }

    @PostMapping("/entity-map")
    fun confirm(@RequestBody req: ConfirmMappingRequest): Map<String, Any> {
        val backfilled = store.confirm(req.source, req.externalPlayerId, req.playerId)
        return mapOf("status" to "confirmed", "source" to req.source, "playerId" to req.playerId, "backfilled" to backfilled)
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

    @PostMapping("/enrichment/run")
    fun runEnrichment(@RequestParam(defaultValue = "50") limit: Int): EnrichmentSummary =
        enrichmentJob.run(limit.coerceIn(1, 200))

    // --- AI weekly digest (generate as DRAFT, review, publish) ---

    @PostMapping("/insights/generate")
    fun generateDigest(): Map<String, Any?> = mapOf("draftId" to weeklyDigestJob.generate())

    @GetMapping("/insights")
    fun insights(@RequestParam(defaultValue = "DRAFT") status: String): List<StoredInsight> =
        digestStore.listByStatus(status.uppercase())

    @PostMapping("/insights/{id}/publish")
    fun publishInsight(@PathVariable id: Long): Map<String, Any> =
        mapOf("id" to id, "published" to digestStore.publish(id))

    /**
     * Debug the RSS half of the digest WITHOUT calling the LLM: returns the articles `RssNewsSource`
     * fetches for the window. Lets you verify feeds are reachable + parse correctly before spending tokens.
     * Optional `days` (freshness window) and `limit` overrides default to the `app.news.*` settings.
     */
    @GetMapping("/news/preview")
    fun newsPreview(
        @RequestParam(required = false) days: Long?,
        @RequestParam(required = false) limit: Int?,
    ): Map<String, Any?> {
        val since = LocalDate.now(ZoneOffset.UTC)
            .minusDays(days ?: newsProps.maxAgeDays)
            .atStartOfDay(ZoneOffset.UTC).toInstant()
        val articles = news.recentArticles(since, (limit ?: newsProps.maxArticles).coerceIn(1, 50))
        return mapOf(
            "enabled" to newsProps.enabled,
            "sites" to scrapers.map { mapOf("publication" to it.publication, "index" to it.indexUrl) },
            "since" to since.toString(),
            "count" to articles.size,
            "articles" to articles.map { it.asContext() },
        )
    }
}
