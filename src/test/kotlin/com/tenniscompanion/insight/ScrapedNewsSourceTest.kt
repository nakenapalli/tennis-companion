package com.tenniscompanion.insight

import com.tenniscompanion.config.NewsProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient
import java.time.Instant

class ScrapedNewsSourceTest {

    // select() doesn't touch the client/scrapers; defaults are fine for construction.
    private val source = ScrapedNewsSource(emptyList(), RestClient.create(), NewsProperties())

    @Test
    fun `select filters by window (keeping undated), dedupes by url, prioritizes mentions, caps`() {
        val now = Instant.parse("2026-06-05T12:00:00Z")
        val since = Instant.parse("2026-06-01T00:00:00Z")
        fun art(url: String, title: String, at: Instant?) = Article(title, null, "Pub", url, at, "")

        val old = art("https://x/old", "Old news", Instant.parse("2026-05-01T00:00:00Z")) // before window
        val undated = art("https://x/undated", "Sinner practices", null)                   // kept (index is newest-first)
        val dupA = art("https://x/a?utm=1", "Alcaraz wins", now)                            // mentions a term
        val dupB = art("https://x/a", "Alcaraz wins again", now.minusSeconds(10))          // same normalized url

        val result = source.select(listOf(old, undated, dupA, dupB), since, 5, prioritize = setOf("Alcaraz"))

        assertFalse(result.any { it.title == "Old news" }, "out of window")
        assertTrue(result.any { it.title == "Sinner practices" }, "undated kept")
        assertFalse(result.any { it.title == "Alcaraz wins again" }, "deduped by normalized url")
        assertEquals("Alcaraz wins", result.first().title, "fact-sheet mention ranks first")
    }
}
