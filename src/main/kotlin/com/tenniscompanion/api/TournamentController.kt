package com.tenniscompanion.api

import com.tenniscompanion.chat.ChatStore
import com.tenniscompanion.chat.ThreadSummaryDto
import com.tenniscompanion.insight.TournamentHeadlines
import com.tenniscompanion.integration.TennisApiAdapter
import com.tenniscompanion.poller.LiveDataStore
import com.tenniscompanion.poller.TournamentStore
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/tournaments")
class TournamentController(
    private val store: TournamentStore,
    private val adapter: TennisApiAdapter,
    private val liveData: LiveDataStore,
    private val chat: ChatStore,
    private val headlines: TournamentHeadlines,
) {
    @GetMapping("/current")
    fun current(): List<TournamentView> = store.current(adapter.source)

    @GetMapping("/{id}")
    fun byId(@PathVariable id: Long): TournamentView = tournament(id)

    /** Today's matches (live + recently finished) for this tournament, importance-sorted. */
    @GetMapping("/{id}/matches")
    fun matches(@PathVariable id: Long): List<LiveMatchDto> =
        liveData.matchesForTournament(adapter.source, tournament(id).name)

    /**
     * Chat threads across the tournament's matches, most-active first. Threads are per-match in Redis, so
     * we fan out over the tournament's matches and pair each thread with its (condensed) match.
     */
    @GetMapping("/{id}/threads")
    fun threads(@PathVariable id: Long): List<TournamentThreadDto> =
        liveData.matchesForTournament(adapter.source, tournament(id).name)
            .flatMap { m -> chat.allThreads(m.externalId).map { t -> t to m } }
            .sortedWith(compareByDescending<Pair<ThreadSummaryDto, LiveMatchDto>> { it.first.activeChatters }.thenByDescending { it.first.createdAt })
            .map { (t, m) -> TournamentThreadDto(m.externalId, t.id, t.title, t.authorName, t.messageCount, t.activeChatters, m) }

    /** Best-effort scraped news headlines mentioning this tournament (cached ~1h). */
    @GetMapping("/{id}/headlines")
    fun headlines(@PathVariable id: Long): List<HeadlineDto> =
        tournament(id).let { headlines.forTournament(it.id, it.name, it.location) }

    private fun tournament(id: Long): TournamentView =
        store.byId(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No tournament with id $id")
}
