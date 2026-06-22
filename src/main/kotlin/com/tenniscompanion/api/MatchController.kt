package com.tenniscompanion.api

import com.tenniscompanion.chat.ChatEventHub
import com.tenniscompanion.chat.ChatMessageDto
import com.tenniscompanion.chat.ChatStore
import com.tenniscompanion.chat.CreateThreadRequest
import com.tenniscompanion.chat.PostMessageRequest
import com.tenniscompanion.chat.ThreadDetailDto
import com.tenniscompanion.chat.ThreadListDto
import com.tenniscompanion.config.ChatProperties
import com.tenniscompanion.domain.UserRepository
import com.tenniscompanion.integration.TennisApiAdapter
import com.tenniscompanion.match.H2hViewDto
import com.tenniscompanion.match.MatchDetailService
import com.tenniscompanion.match.MatchStatsDto
import com.tenniscompanion.match.MomentumDto
import com.tenniscompanion.match.PlayersViewDto
import com.tenniscompanion.poller.LiveDataStore
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

/**
 * The dedicated match view: match detail + cache-only chat. GETs are public (SecurityConfig); POSTs require
 * a logged-in user. Posting is rejected (423) once the match has finished — threads stay viewable until their
 * TTL purges them. Live updates are pushed over SSE (`/stream` endpoints) on top of the REST snapshots.
 */
@RestController
@RequestMapping("/api/matches")
class MatchController(
    private val liveData: LiveDataStore,
    private val adapter: TennisApiAdapter,
    private val matchDetail: MatchDetailService,
    private val chat: ChatStore,
    private val hub: ChatEventHub,
    private val users: UserRepository,
    private val props: ChatProperties,
) {

    @GetMapping("/{externalId}")
    fun match(@PathVariable externalId: String): LiveMatchDto =
        liveData.matchDetail(adapter.source, externalId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No match $externalId")

    /** Momentum-tab data (bespoke metric over the point-by-point flow). 404 when the feed carries no points. */
    @GetMapping("/{externalId}/momentum")
    fun momentum(@PathVariable externalId: String): MomentumDto =
        matchDetail.momentum(externalId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No momentum data for $externalId")

    /** Stats-tab data (per-period serve/return/points/games comparison). 404 when the feed carries no stats. */
    @GetMapping("/{externalId}/stats")
    fun stats(@PathVariable externalId: String): MatchStatsDto =
        matchDetail.stats(externalId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No stats for $externalId")

    /** Head-to-head tab (Sackmann history for reconciled players, else live feed). 404 if no meetings. */
    @GetMapping("/{externalId}/h2h")
    fun h2h(@PathVariable externalId: String): H2hViewDto =
        matchDetail.h2h(externalId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No head-to-head for $externalId")

    /** Players tab: side-by-side bios. 404 only when the match itself is unknown. */
    @GetMapping("/{externalId}/players")
    fun players(@PathVariable externalId: String): PlayersViewDto =
        matchDetail.players(externalId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No player info for $externalId")

    @GetMapping("/{externalId}/threads")
    fun threads(@PathVariable externalId: String): ThreadListDto =
        chat.listThreads(externalId, locked = isLocked(externalId))

    @PostMapping("/{externalId}/threads")
    fun createThread(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable externalId: String,
        @RequestBody req: CreateThreadRequest,
    ): ThreadDetailDto {
        requireOpen(externalId)
        val (uid, name) = author(jwt)
        val thread = chat.createThread(externalId, uid, name, clean(req.title, props.maxTitleLen, "title"))
        hub.publishThreadsChanged(externalId, thread.id)
        return thread
    }

    @GetMapping("/{externalId}/threads/{threadId}")
    fun thread(@PathVariable externalId: String, @PathVariable threadId: String): ThreadDetailDto =
        chat.thread(externalId, threadId, locked = isLocked(externalId))
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No thread $threadId")

    @PostMapping("/{externalId}/threads/{threadId}/messages")
    fun postMessage(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable externalId: String,
        @PathVariable threadId: String,
        @RequestBody req: PostMessageRequest,
    ): ChatMessageDto {
        requireOpen(externalId)
        val (uid, name) = author(jwt)
        val msg = chat.postMessage(externalId, threadId, uid, name, clean(req.text, props.maxMessageLen, "message"))
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No thread $threadId")
        hub.publishMessage(externalId, threadId, msg)
        hub.publishThreadsChanged(externalId, threadId)
        return msg
    }

    @GetMapping("/{externalId}/threads/stream")
    fun streamThreads(@PathVariable externalId: String): SseEmitter = hub.subscribeList(externalId)

    @GetMapping("/{externalId}/threads/{threadId}/stream")
    fun streamThread(@PathVariable externalId: String, @PathVariable threadId: String): SseEmitter =
        hub.subscribeThread(externalId, threadId)

    private fun isLocked(externalId: String): Boolean = liveData.matchStatus(adapter.source, externalId) == "finished"

    private fun requireOpen(externalId: String) {
        when (liveData.matchStatus(adapter.source, externalId)) {
            null -> throw ResponseStatusException(HttpStatus.NOT_FOUND, "No match $externalId")
            "finished" -> throw ResponseStatusException(HttpStatus.LOCKED, "Chat is locked — the match has ended")
        }
    }

    private fun clean(raw: String, max: Int, what: String): String {
        val t = raw.trim()
        if (t.isEmpty() || t.length > max) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "$what must be 1-$max characters")
        return t
    }

    /** (userId, displayName) — name from the JWT username claim, falling back to the DB then a placeholder. */
    private fun author(jwt: Jwt): Pair<Long, String> {
        val uid = jwt.subject.toLong()
        val name = jwt.getClaimAsString("username")?.takeUnless { it.isBlank() }
            ?: users.findById(uid).orElse(null)?.username
            ?: "user$uid"
        return uid to name
    }
}
