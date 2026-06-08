package com.tenniscompanion.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Per-match live chat (cache-only). Threads/messages live in Redis with a [ttl] and are purged after it —
 * nothing is persisted to Postgres. [activeWindow] defines how recently a user must have posted to count
 * as an "active chatter" for the top-threads ranking.
 */
@ConfigurationProperties(prefix = "app.chat")
data class ChatProperties(
    val enabled: Boolean = true,
    val ttl: Duration = Duration.ofDays(1),
    val activeWindow: Duration = Duration.ofMinutes(15),
    val topThreads: Int = 3,
    val latestThreads: Int = 5,
    val maxThreadsPerMatch: Int = 100,
    val maxMessagesPerThread: Int = 500,
    val maxTitleLen: Int = 120,
    val maxMessageLen: Int = 500,
)
