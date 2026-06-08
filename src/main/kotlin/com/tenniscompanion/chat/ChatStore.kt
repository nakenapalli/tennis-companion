package com.tenniscompanion.chat

import com.tenniscompanion.config.ChatProperties
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import java.time.Instant
import java.util.UUID

/**
 * Redis-only chat storage, scoped per match externalId. Keys:
 *   chat:{m}:threads          (hash)  threadId -> ThreadMeta JSON
 *   chat:{m}:t:{tid}          (list)  message JSON (trimmed to maxMessagesPerThread)
 *   chat:{m}:t:{tid}:chatters (zset)  userId -> last-activity epoch (for the active-chatter ranking)
 * Every write refreshes the touched keys' TTL to [ChatProperties.ttl]; reads don't. Posting is blocked once
 * the match is finished (locked, enforced by the controller), so chat purges ~1 day after the last message.
 */
@Repository
class ChatStore(
    private val redis: StringRedisTemplate,
    private val mapper: ObjectMapper,
    private val props: ChatProperties,
) {

    fun createThread(matchId: String, authorId: Long, authorName: String, title: String): ThreadDetailDto {
        val key = threadsKey(matchId)
        val hash = redis.opsForHash<String, String>()
        check((hash.size(key) ?: 0) < props.maxThreadsPerMatch) { "thread limit reached for this match" }
        val meta = ThreadMeta(UUID.randomUUID().toString(), title, authorId, authorName, now(), 0)
        hash.put(key, meta.id, mapper.writeValueAsString(meta))
        redis.expire(key, props.ttl)
        return ThreadDetailDto(meta.id, meta.title, meta.authorName, Instant.ofEpochMilli(meta.createdAt), emptyList(), false)
    }

    /** Append a message; returns null if the thread doesn't exist. */
    fun postMessage(matchId: String, threadId: String, authorId: Long, authorName: String, text: String): ChatMessageDto? {
        val tKey = threadsKey(matchId)
        val hash = redis.opsForHash<String, String>()
        val meta = hash.get(tKey, threadId)?.let { mapper.readValue<ThreadMeta>(it) } ?: return null

        val msg = StoredMessage(UUID.randomUUID().toString(), authorId, authorName, text, now())
        val mKey = msgsKey(matchId, threadId)
        redis.opsForList().rightPush(mKey, mapper.writeValueAsString(msg))
        redis.opsForList().trim(mKey, -props.maxMessagesPerThread.toLong(), -1)
        val size = (redis.opsForList().size(mKey) ?: 0L).toInt()

        val cKey = chattersKey(matchId, threadId)
        redis.opsForZSet().add(cKey, authorId.toString(), msg.createdAt.toDouble())
        hash.put(tKey, threadId, mapper.writeValueAsString(meta.copy(messageCount = size)))

        for (k in listOf(tKey, mKey, cKey)) redis.expire(k, props.ttl)
        return ChatMessageDto(msg.id, msg.authorName, msg.text, Instant.ofEpochMilli(msg.createdAt))
    }

    fun listThreads(matchId: String, locked: Boolean): ThreadListDto {
        val metas = redis.opsForHash<String, String>().values(threadsKey(matchId)).map { mapper.readValue<ThreadMeta>(it) }
        if (metas.isEmpty()) return ThreadListDto(emptyList(), emptyList(), locked)

        val active = metas
            .map { it to activeChatters(matchId, it.id) }
            .sortedWith(compareByDescending<Pair<ThreadMeta, Int>> { it.second }.thenByDescending { it.first.createdAt })
            .take(props.topThreads)
        val activeIds = active.mapTo(HashSet()) { it.first.id }
        val latest = metas.filter { it.id !in activeIds }
            .sortedByDescending { it.createdAt }
            .take(props.latestThreads)

        return ThreadListDto(
            active = active.map { summary(it.first, it.second) },
            latest = latest.map { summary(it, activeChatters(matchId, it.id)) },
            locked = locked,
        )
    }

    fun thread(matchId: String, threadId: String, locked: Boolean): ThreadDetailDto? {
        val meta = redis.opsForHash<String, String>().get(threadsKey(matchId), threadId)
            ?.let { mapper.readValue<ThreadMeta>(it) } ?: return null
        val messages = redis.opsForList().range(msgsKey(matchId, threadId), 0, -1).orEmpty()
            .map { mapper.readValue<StoredMessage>(it) }
            .map { ChatMessageDto(it.id, it.authorName, it.text, Instant.ofEpochMilli(it.createdAt)) }
        return ThreadDetailDto(meta.id, meta.title, meta.authorName, Instant.ofEpochMilli(meta.createdAt), messages, locked)
    }

    private fun activeChatters(matchId: String, threadId: String): Int {
        val now = now()
        val from = now - props.activeWindow.toMillis()
        return (redis.opsForZSet().count(chattersKey(matchId, threadId), from.toDouble(), now.toDouble()) ?: 0L).toInt()
    }

    private fun summary(m: ThreadMeta, active: Int) =
        ThreadSummaryDto(m.id, m.title, m.authorName, Instant.ofEpochMilli(m.createdAt), m.messageCount, active)

    private fun now() = Instant.now().toEpochMilli()
    private fun threadsKey(m: String) = "chat:$m:threads"
    private fun msgsKey(m: String, t: String) = "chat:$m:t:$t"
    private fun chattersKey(m: String, t: String) = "chat:$m:t:$t:chatters"
}
