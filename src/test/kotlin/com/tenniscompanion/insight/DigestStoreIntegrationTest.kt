package com.tenniscompanion.insight

import com.tenniscompanion.TestcontainersConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import tools.jackson.databind.ObjectMapper

/**
 * Exercises the digest store (and V6 migration) on a throwaway Postgres, plus the fence-strip + parse
 * path with the real Spring ObjectMapper. The full job (factsheet → LLM → store) is verified live.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@TestPropertySource(properties = ["app.poll.enabled=false"])
class DigestStoreIntegrationTest(
    @Autowired val store: DigestStore,
    @Autowired val mapper: ObjectMapper,
) {

    @Test
    fun `draft is stored, then published, then served as latest`() {
        val factSheet = mapOf("week_of" to "2026-06-02", "tournaments" to listOf("French Open"))
        val id = store.saveDraft("weekly_digest", "Clay drama", "## Body\nText", factSheet, "claude-sonnet-4-6")

        // visible as a draft; not yet published
        assertTrue(store.listByStatus("DRAFT").any { it.id == id })
        assertNull(store.latestPublished("weekly_digest"))

        // publish flips it; second publish is a no-op
        assertTrue(store.publish(id))
        assertFalse(store.publish(id))

        val latest = store.latestPublished("weekly_digest")
        assertEquals(id, latest?.id)
        assertEquals("Clay drama", latest?.title)
        assertEquals("PUBLISHED", store.byId(id)?.status)
    }

    @Test
    fun `parse handles a fenced JSON response`() {
        val raw = "```json\n{\"title\": \"T\", \"body_markdown\": \"B\"}\n```"
        val result = DigestParsing.parse(mapper, raw)
        assertEquals("T", result.title)
        assertEquals("B", result.bodyMarkdown)
    }
}
