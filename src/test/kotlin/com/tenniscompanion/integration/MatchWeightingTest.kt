package com.tenniscompanion.integration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper

class MatchWeightingTest {

    // Registry loads tournament-tiers.json from the (main) classpath; no Spring needed.
    private val weighting = MatchWeighting(TournamentTierRegistry(jacksonObjectMapper()))

    @Test
    fun `grand slam final outranks a lower-tour semifinal`() {
        val slamFinal = weighting.weight("French Open", "WTA", "WTA French Open - Final")
        val tourSemi = weighting.weight("Hertogenbosch", "ATP", "ATP Hertogenbosch - Semi-finals")
        assertTrue(slamFinal > tourSemi, "FO final ($slamFinal) should outrank Hertogenbosch SF ($tourSemi)")
    }

    @Test
    fun `slam name aliases resolve to the same tier`() {
        assertEquals(
            weighting.weight("Roland Garros", "WTA", "X - Final"),
            weighting.weight("French Open", "WTA", "X - Final"),
        )
    }

    @Test
    fun `juniors are demoted even at a slam`() {
        val junior = weighting.weight("French Open", "Junior", "Girls French Open - Final")
        val tour = weighting.weight("Hertogenbosch", "ATP", "X - Final")
        assertTrue(junior < tour, "junior ($junior) should rank below a tour event ($tour)")
    }

    @Test
    fun `round bonus parses the suffix after the last dash`() {
        assertEquals(50, weighting.roundBonus("WTA French Open - Final"))
        assertEquals(40, weighting.roundBonus("ATP Hertogenbosch - Semi-finals"))
        assertEquals(30, weighting.roundBonus("ATP Foo - Quarter-finals"))
        assertEquals(20, weighting.roundBonus("ATP Foo - Round of 16"))
        assertEquals(0, weighting.roundBonus("ATP Foo - Round of 32"))
        assertEquals(0, weighting.roundBonus(null))
    }

    @Test
    fun `unknown tournament falls back to the feed category`() {
        val unknown = weighting.weight("Some Random Open", "ATP", "X - Final") // not in the curated map
        val slam = weighting.weight("Wimbledon", "ATP", "X - Final")
        assertTrue(unknown < slam)
    }
}
