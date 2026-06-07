package com.tenniscompanion.insight

import com.tenniscompanion.config.NewsProperties
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.net.URI
import java.time.Instant

/**
 * Fetches recent tennis articles by scraping the configured per-site [SiteScraper]s. Bodies are used only
 * transiently as LLM context (never stored). Resilient: a failing site or article is logged and skipped
 * so it never breaks digest generation.
 */
@Component
class ScrapedNewsSource(
    private val scrapers: List<SiteScraper>,
    @Qualifier("newsRestClient") private val client: RestClient,
    private val props: NewsProperties,
) : NewsSource {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun recentArticles(since: Instant, limit: Int, prioritize: Set<String>): List<Article> {
        if (!props.enabled || scrapers.isEmpty()) return emptyList()
        val all = scrapers.flatMap { scraper ->
            runCatching { scrape(scraper, limit) }
                .getOrElse { log.warn("News site failed, skipping: {} ({})", scraper.publication, it.message); emptyList() }
        }
        return select(all, since, limit, prioritize)
    }

    private fun scrape(scraper: SiteScraper, limit: Int): List<Article> {
        val indexHtml = get(scraper.indexUrl) ?: return emptyList()
        // Over-fetch a little past the cap (window/dedupe may trim), but stay polite.
        return scraper.articleLinks(indexHtml).take(limit + 2).mapNotNull { url ->
            runCatching { get(url)?.let { scraper.parseArticle(url, it) } }
                .getOrElse { log.warn("Article failed, skipping: {} ({})", url, it.message); null }
                ?.let { it.copy(text = it.text.take(props.perArticleCharCap)) }
        }
    }

    private fun get(url: String): String? =
        client.get().uri(URI.create(url)).retrieve().body(String::class.java)

    /** Filter to the freshness window (keeping undated index articles), dedupe, rank, cap. */
    internal fun select(all: List<Article>, since: Instant, limit: Int, prioritize: Set<String>): List<Article> {
        val terms = prioritize
            .flatMap { it.lowercase().split(Regex("[^a-z0-9]+")) }
            .filter { it.length >= 4 }
            .toSet()
        fun relevance(a: Article): Int {
            if (terms.isEmpty()) return 0
            val hay = (a.title + " " + a.text).lowercase()
            return terms.count { hay.contains(it) }
        }
        return all.asSequence()
            // index pages are newest-first, so keep an article with no parseable date rather than drop it
            .filter { it.publishedAt == null || !it.publishedAt!!.isBefore(since) }
            .distinctBy { normalizeUrl(it.url) }
            .sortedWith(
                compareByDescending<Article> { relevance(it) }
                    .thenByDescending { it.publishedAt ?: Instant.MIN },
            )
            .take(limit)
            .toList()
    }

    private fun normalizeUrl(u: String): String = u.trim().substringBefore("?").trimEnd('/').lowercase()
}
