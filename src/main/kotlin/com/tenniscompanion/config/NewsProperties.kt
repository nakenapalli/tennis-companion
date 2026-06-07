package com.tenniscompanion.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * News scraping for the weekly digest's cited context. Bound from `app.news.*`. The sites themselves are
 * code (one `SiteScraper` per source); these are the runtime knobs. Articles are used only transiently as
 * LLM context and never persisted.
 */
@ConfigurationProperties(prefix = "app.news")
data class NewsProperties(
    val enabled: Boolean = true,
    /** Max articles handed to the LLM per digest (full bodies are large — keep small). */
    val maxArticles: Int = 4,
    /** Only consider articles published within this many days of the digest week. */
    val maxAgeDays: Long = 8,
    /** Truncate each article body to this many characters before it goes into the prompt. */
    val perArticleCharCap: Int = 4000,
)
