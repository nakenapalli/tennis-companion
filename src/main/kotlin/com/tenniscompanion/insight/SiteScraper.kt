package com.tenniscompanion.insight

/**
 * A per-site scraper for one manually-chosen news source. Parsing is pure (takes HTML strings) so it's
 * unit-testable without HTTP; [ScrapedNewsSource] does the fetching. Adding/removing a source is a
 * one-class change — register a new `@Component` implementing this.
 */
interface SiteScraper {
    /** Display name used as the citation publication (e.g. "Tennis.com"). */
    val publication: String

    /** The listing page to discover recent article URLs from. */
    val indexUrl: String

    /** Absolute article URLs found on the listing page (newest-first as the page presents them). */
    fun articleLinks(indexHtml: String): List<String>

    /** Parse one article page into an [Article], or null if it doesn't look like a usable article. */
    fun parseArticle(url: String, html: String): Article?
}
