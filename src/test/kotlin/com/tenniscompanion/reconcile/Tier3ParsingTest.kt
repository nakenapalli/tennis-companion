package com.tenniscompanion.reconcile

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper

class Tier3ParsingTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `parses a plain JSON decision`() {
        val d = Tier3Parsing.parse(mapper, """{"player_id": 207989, "confidence": 0.95, "rationale": "country and rank align"}""")
        assertEquals(207989L, d.playerId)
        assertEquals(0.95, d.confidence)
        assertEquals("country and rank align", d.rationale)
    }

    @Test
    fun `parses a null (no match) decision`() {
        val d = Tier3Parsing.parse(mapper, """{"player_id": null, "confidence": 0.88, "rationale": "no candidate shares the surname"}""")
        assertNull(d.playerId)
        assertEquals(0.88, d.confidence)
    }

    @Test
    fun `strips a stray code fence`() {
        val d = Tier3Parsing.parse(mapper, "```json\n{\"player_id\": 1, \"confidence\": 0.6, \"rationale\": \"y\"}\n```")
        assertEquals(1L, d.playerId)
    }

    @Test
    fun `validation accepts an offered id or null and rejects an invented one`() {
        val offered = setOf(1L, 2L)
        assertTrue(Tier3Parsing.isValid(Tier3Parsing.Tier3Decision(1L, 0.9), offered))
        assertTrue(Tier3Parsing.isValid(Tier3Parsing.Tier3Decision(null, 0.7), offered))
        assertFalse(Tier3Parsing.isValid(Tier3Parsing.Tier3Decision(999L, 0.99), offered)) // model invented an id
    }
}
