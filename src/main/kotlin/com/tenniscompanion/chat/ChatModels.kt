package com.tenniscompanion.chat

import java.time.Instant

// --- API DTOs ---

data class ThreadSummaryDto(
    val id: String,
    val title: String,
    val authorName: String,
    val createdAt: Instant,
    val messageCount: Int,
    val activeChatters: Int,
)

data class ChatMessageDto(
    val id: String,
    val authorName: String,
    val text: String,
    val createdAt: Instant,
)

data class ThreadDetailDto(
    val id: String,
    val title: String,
    val authorName: String,
    val createdAt: Instant,
    val messages: List<ChatMessageDto>,
    val locked: Boolean,
)

/** Top-3 by active chatters, then the latest-5 by creation (deduped). */
data class ThreadListDto(
    val active: List<ThreadSummaryDto>,
    val latest: List<ThreadSummaryDto>,
    val locked: Boolean,
)

data class CreateThreadRequest(val title: String = "")
data class PostMessageRequest(val text: String = "")

// --- Redis-stored shapes (epoch millis; never persisted to Postgres) ---

internal data class ThreadMeta(
    val id: String,
    val title: String,
    val authorId: Long,
    val authorName: String,
    val createdAt: Long,
    val messageCount: Int,
)

internal data class StoredMessage(
    val id: String,
    val authorId: Long,
    val authorName: String,
    val text: String,
    val createdAt: Long,
)
