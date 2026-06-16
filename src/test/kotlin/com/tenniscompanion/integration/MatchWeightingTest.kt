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
    fun `round bonus handles the feed's fraction round names`() {
        // The live feed emits early rounds as "1/N-finals" — all *contain* "final" but must not earn the final bonus.
        assertEquals(50, weighting.roundBonus("WTA Berlin - Final"))
        assertEquals(0, weighting.roundBonus("WTA Berlin - 1/16-finals")) // round of 32 — was wrongly 50
        assertEquals(0, weighting.roundBonus("WTA Berlin - 1/32-finals"))
        assertEquals(20, weighting.roundBonus("ATP Halle - 1/8-finals")) // round of 16
        assertEquals(30, weighting.roundBonus("ATP Foo - 1/4-finals"))
        assertEquals(40, weighting.roundBonus("ATP Foo - 1/2-finals"))
    }

    @Test
    fun `within a tournament the final outranks an early round`() {
        val final = weighting.weight("Berlin", "WTA", "WTA Berlin - Final")
        val early = weighting.weight("Berlin", "WTA", "WTA Berlin - 1/16-finals")
        assertTrue(final > early, "Berlin final ($final) should outrank Berlin 1/16-finals ($early)")
    }

    @Test
    fun `qualifying matches get no round bonus and rank below the main draw of the same tier`() {
        // A qualifying "Final" reuses the main-draw round name; it must NOT be weighted like the real final.
        val qualFinal = weighting.weight("Berlin", "WTA", "WTA Berlin - Final", qualifying = true)
        val mainFinal = weighting.weight("Berlin", "WTA", "WTA Berlin - Final")
        val mainEarliest = weighting.weight("Berlin", "WTA", "WTA Berlin - 1/16-finals") // round bonus 0
        assertTrue(qualFinal < mainFinal, "qualifying final ($qualFinal) below the main-draw final ($mainFinal)")
        assertTrue(qualFinal < mainEarliest, "qualifying ($qualFinal) below every main-draw match of its tier ($mainEarliest)")
        // …but still ranked within its tier, above a lower-tier event
        val lowerTier = weighting.weight("Some Challenger", "Challenger", "X - Final")
        assertTrue(qualFinal > lowerTier, "WTA 500 qualifying ($qualFinal) still above a Challenger ($lowerTier)")
    }

    @Test
    fun `unknown tournament falls back to the feed category`() {
        val unknown = weighting.weight("Some Random Open", "ATP", "X - Final") // not in the curated map
        val slam = weighting.weight("Wimbledon", "ATP", "X - Final")
        assertTrue(unknown < slam)
    }
}
