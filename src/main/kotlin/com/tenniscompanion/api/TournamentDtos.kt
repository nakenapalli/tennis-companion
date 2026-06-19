package com.tenniscompanion.api

import java.time.Instant

/**
 * A chat thread surfaced on a tournament's Threads tab. Threads are per-match (Redis), so we carry the
 * owning match (`matchExternalId` for routing + the condensed `match` for the score) alongside the thread.
 */
data class TournamentThreadDto(
    val matchExternalId: String,
    val threadId: String,
    val title: String,
    val authorName: String,
    val messageCount: Int,
    val activeChatters: Int,
    val match: LiveMatchDto,
)

/**
 * A news headline for a tournament's Overview tab. Only metadata (no article body) — article text is used
 * transiently for digest generation and never persisted; here we cache just the link + title in Redis.
 */
data class HeadlineDto(
    val title: String,
    val publication: String,
    val url: String,
    val publishedAt: Instant?,
)
