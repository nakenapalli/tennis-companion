package com.tenniscompanion.chat

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory SSE fan-out for chat (Spring MVC `SseEmitter`; no new dependency). Browsers' `EventSource` can't
 * send auth headers and reading chat is public, so the stream endpoints are unauthenticated; posting stays
 * authenticated. Single-instance only — a multi-instance deployment would back this with Redis pub/sub.
 */
@Component
class ChatEventHub {

    private val listSubs = ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>>() // matchId -> emitters
    private val threadSubs = ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>>() // "matchId::threadId" -> emitters

    fun subscribeList(matchId: String): SseEmitter = subscribe(listSubs, matchId)

    fun subscribeThread(matchId: String, threadId: String): SseEmitter = subscribe(threadSubs, key(matchId, threadId))

    /** A thread was created or its activity changed — list subscribers should revalidate. */
    fun publishThreadsChanged(matchId: String, threadId: String) = send(listSubs[matchId], "thread-changed", threadId)

    /** A new message in a thread — thread subscribers append it. */
    fun publishMessage(matchId: String, threadId: String, message: ChatMessageDto) =
        send(threadSubs[key(matchId, threadId)], "message", message)

    private fun subscribe(map: ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>>, k: String): SseEmitter {
        val emitter = SseEmitter(0L) // no timeout; the heartbeat keeps it alive
        val list = map.computeIfAbsent(k) { CopyOnWriteArrayList() }
        list.add(emitter)
        emitter.onCompletion { list.remove(emitter) }
        emitter.onTimeout { list.remove(emitter); emitter.complete() }
        emitter.onError { list.remove(emitter) }
        runCatching { emitter.send(SseEmitter.event().comment("connected")) }
        return emitter
    }

    private fun send(subs: CopyOnWriteArrayList<SseEmitter>?, name: String, data: Any) {
        subs ?: return
        for (e in subs) {
            runCatching { e.send(SseEmitter.event().name(name).data(data)) }
                .onFailure { subs.remove(e); runCatching { e.complete() } }
        }
    }

    @Scheduled(fixedRate = 20_000)
    fun heartbeat() {
        for (subs in listSubs.values + threadSubs.values) {
            for (e in subs) {
                runCatching { e.send(SseEmitter.event().comment("ping")) }
                    .onFailure { subs.remove(e); runCatching { e.complete() } }
            }
        }
    }

    private fun key(matchId: String, threadId: String) = "$matchId::$threadId"
}
