package com.tenniscompanion.insight

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TennisDotComScraperTest {

    private val scraper = TennisDotComScraper()

    private val indexHtml = """
        <html><body>
          <a href="/news/articles/player-a-wins-in-paris">A wins</a>
          <a href="/news/articles/player-b-advances">B advances</a>
          <a href="/news/articles/player-a-wins-in-paris#comments">same article, anchor</a>
          <a href="/rankings">Rankings</a>
          <a href="https://www.tennis.com/news/articles/c-into-final">absolute link</a>
        </body></html>
    """.trimIndent()

    @Test
    fun `articleLinks finds article urls, absolutizes, dedupes, excludes non-articles`() {
        val links = scraper.articleLinks(indexHtml)
        assertTrue(links.contains("https://www.tennis.com/news/articles/player-a-wins-in-paris"))
        assertTrue(links.contains("https://www.tennis.com/news/articles/player-b-advances"))
        assertTrue(links.contains("https://www.tennis.com/news/articles/c-into-final"))
        assertFalse(links.any { it.contains("/rankings") }, "non-article link excluded")
        assertEquals(1, links.count { it.endsWith("/news/articles/player-a-wins-in-paris") }, "anchor dup collapsed")
    }

    // Synthetic article markup (not real content) exercising the meta-first extraction with fallbacks.
    private val articleHtml = """
        <html><head>
          <meta property="og:title" content="Player A wins in Paris"/>
          <meta name="author" content="Jane Doe"/>
          <meta property="article:published_time" content="2026-06-05T10:00:00Z"/>
        </head><body>
          <nav><p>Home Scores Rankings</p></nav>
          <article>
            <p>Short.</p>
            <p>Player A defeated Player B in three sets to reach the next round of the tournament.</p>
            <p>It capped a strong week of preparation on the clay courts ahead of the final stretch.</p>
          </article>
        </body></html>
    """.trimIndent()

    @Test
    fun `parseArticle extracts title, author, date, and body paragraphs`() {
        val a = scraper.parseArticle("https://www.tennis.com/news/articles/player-a-wins-in-paris", articleHtml)!!
        assertEquals("Player A wins in Paris", a.title)
        assertEquals("Jane Doe", a.author)
        assertEquals("Tennis.com", a.publication)
        assertNotNull(a.publishedAt)
        assertTrue(a.text.contains("Player A defeated Player B"))
        assertFalse(a.text.contains("Short."), "sub-40-char fragment dropped")
        assertFalse(a.text.contains("Home Scores Rankings"), "nav outside <article> excluded")
    }
}
