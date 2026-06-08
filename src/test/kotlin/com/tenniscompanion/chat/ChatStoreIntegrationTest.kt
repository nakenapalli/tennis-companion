package com.tenniscompanion.chat

import com.tenniscompanion.TestcontainersConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.TestPropertySource

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@TestPropertySource(properties = ["app.poll.enabled=false"])
class ChatStoreIntegrationTest(
    @Autowired val chat: ChatStore,
    @Autowired val redis: StringRedisTemplate,
) {

    @Test
    fun `posts a message and reads the thread back`() {
        val m = "match-msg"
        val t = chat.createThread(m, 1, "alice", "Who wins?").id
        assertNull(chat.postMessage(m, "does-not-exist", 1, "alice", "hi"), "unknown thread -> null")

        chat.postMessage(m, t, 1, "alice", "Sinner in 4")
        chat.postMessage(m, t, 2, "bob", "no chance")

        val detail = chat.thread(m, t, locked = false)!!
        assertEquals(2, detail.messages.size)
        assertEquals(listOf("Sinner in 4", "no chance"), detail.messages.map { it.text })
        assertEquals(listOf("alice", "bob"), detail.messages.map { it.authorName })
        assertFalse(detail.locked)
    }

    @Test
    fun `lists top-3 by active chatters then latest, deduped`() {
        val m = "match-list"
        val a = chat.createThread(m, 1, "u", "A").id
        val b = chat.createThread(m, 1, "u", "B").id
        val c = chat.createThread(m, 1, "u", "C").id
        val d = chat.createThread(m, 1, "u", "D").id

        // distinct chatters: C=3, A=2, B=1, D=0
        listOf(10L, 11L, 12L).forEach { chat.postMessage(m, c, it, "u$it", "x") }
        listOf(10L, 11L).forEach { chat.postMessage(m, a, it, "u$it", "x") }
        chat.postMessage(m, b, 10, "u10", "x")

        val list = chat.listThreads(m, locked = true)
        assertEquals(listOf(c, a, b), list.active.map { it.id }, "top-3 by active chatters")
        assertEquals(3, list.active.first().activeChatters)
        assertEquals(listOf(d), list.latest.map { it.id }, "remaining, newest first, deduped from active")
        assertTrue(list.locked)
    }

    @Test
    fun `writes set a TTL on the chat keys (cache-only, auto-purge)`() {
        val m = "match-ttl"
        val t = chat.createThread(m, 1, "alice", "Topic").id
        chat.postMessage(m, t, 1, "alice", "hi")
        assertTrue((redis.getExpire("chat:$m:threads")) > 0, "threads key has a TTL")
        assertTrue((redis.getExpire("chat:$m:t:$t")) > 0, "messages key has a TTL")
    }
}
