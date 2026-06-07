package com.tenniscompanion.insight

import java.time.Instant

/**
 * A recent tennis news article — metadata plus the extracted body text. Used ONLY transiently as LLM
 * context during digest generation; never persisted. Provider-isolated like the upstream
 * `TennisApiAdapter`: the digest depends on this seam, not on any one site's scraping specifics.
 */
data class Article(
    val title: String,
    val author: String?,
    val publication: String,
    val url: String,
    val publishedAt: Instant?,
    val text: String,
) {
    /** What the LLM sees. Cite via the url. (Not stored — articles are never persisted.) */
    fun asContext(): Map<String, Any?> = linkedMapOf(
        "title" to title,
        "author" to author,
        "publication" to publication,
        "url" to url,
        "published" to publishedAt?.toString(),
        "content" to text,
    )
}

interface NewsSource {
    /**
     * Recent articles published at/after [since], capped at [limit]. Articles mentioning a term in
     * [prioritize] (e.g. the week's player/tournament names) are ranked ahead of the rest. Returns empty
     * (never throws) when disabled or unreachable — the caller decides what to do with no articles.
     */
    fun recentArticles(since: Instant, limit: Int, prioritize: Set<String> = emptySet()): List<Article>
}
