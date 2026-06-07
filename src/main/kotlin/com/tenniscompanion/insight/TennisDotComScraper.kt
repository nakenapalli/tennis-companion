package com.tenniscompanion.insight

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Scraper for tennis.com. The `/news` index links articles at `/news/articles/<slug>`; each article page
 * is server-rendered with the full body, a byline, and a publish date. Extraction prefers stable metadata
 * (Open Graph / article meta tags) and falls back to visible text, so it tolerates layout drift.
 */
@Component
class TennisDotComScraper : SiteScraper {

    override val publication = "Tennis.com"
    override val indexUrl = "$BASE/news"

    override fun articleLinks(indexHtml: String): List<String> {
        val doc = Jsoup.parse(indexHtml, BASE)
        return doc.select("a[href*=/news/articles/]")
            .map { it.attr("abs:href").substringBefore('#').substringBefore('?') }
            .filter { it.contains("/news/articles/") }
            .distinct()
    }

    override fun parseArticle(url: String, html: String): Article? {
        val doc = Jsoup.parse(html, BASE)
        val title = (metaContent(doc, "meta[property=og:title]") ?: doc.selectFirst("h1")?.text()?.trim())
            ?.takeUnless { it.isBlank() } ?: return null
        val body = extractBody(doc)
        if (body.isBlank()) return null
        val author = metaContent(doc, "meta[name=author]")
            ?: metaContent(doc, "meta[property=article:author]")
            ?: publication
        return Article(title, author, publication, url, parseDate(doc), body)
    }

    private fun metaContent(doc: Document, selector: String): String? =
        doc.selectFirst(selector)?.attr("content")?.trim()?.takeUnless { it.isBlank() }

    private fun parseDate(doc: Document): Instant? {
        val raw = metaContent(doc, "meta[property=article:published_time]")
            ?: metaContent(doc, "meta[name=pubdate]")
            ?: doc.selectFirst("time[datetime]")?.attr("datetime")?.trim()?.takeUnless { it.isBlank() }
        if (raw != null) {
            runCatching { return OffsetDateTime.parse(raw).toInstant() }
            runCatching { return Instant.parse(raw) }
            runCatching { return LocalDate.parse(raw).atStartOfDay(ZoneOffset.UTC).toInstant() }
        }
        // Fallback: a visible "Published Jun 05, 2026" line.
        return PUBLISHED_RE.find(doc.text())?.let {
            runCatching { LocalDate.parse(it.groupValues[1], DATE_FMT).atStartOfDay(ZoneOffset.UTC).toInstant() }.getOrNull()
        }
    }

    private fun extractBody(doc: Document): String {
        val container = doc.selectFirst("article") ?: doc.selectFirst("main") ?: doc.body()
        return container.select("p")
            .map { it.text().trim() }
            .filter { it.length >= MIN_PARAGRAPH } // drop nav crumbs / teasers
            .joinToString("\n\n")
            .trim()
    }

    private companion object {
        const val BASE = "https://www.tennis.com"
        const val MIN_PARAGRAPH = 40
        val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)
        val PUBLISHED_RE = Regex("""Published\s+([A-Z][a-z]{2}\s+\d{1,2},\s+\d{4})""")
    }
}
