package com.tenniscompanion.insight

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MatchFactsTest {

    private fun score(home: List<Int>, away: List<Int>): Map<String, Any?> =
        mapOf("home" to mapOf("sets" to home), "away" to mapOf("sets" to away))

    @Test
    fun `winnerOf counts sets won`() {
        assertEquals("home", MatchFacts.winnerOf(score(listOf(6, 4, 6), listOf(3, 6, 0)))) // home wins 2-1
        assertEquals("away", MatchFacts.winnerOf(score(listOf(6, 4, 2), listOf(3, 6, 6)))) // away wins 2-1
        assertNull(MatchFacts.winnerOf(null))
        assertNull(MatchFacts.winnerOf(score(listOf(6), listOf(6)))) // undeterminable
    }

    @Test
    fun `resultLine is winner-first and keeps every set`() {
        // home win including a dropped middle set — must not be omitted or reordered
        assertEquals(
            "Shnaider beats Keys 6-3, 4-6, 6-0",
            MatchFacts.resultLine("Shnaider", "Keys", score(listOf(6, 4, 6), listOf(3, 6, 0)), "home"),
        )
        // away win of a five-setter — winner's games first in every set
        assertEquals(
            "Arnaldi beats Tiafoe 7-6, 6-7, 3-6, 7-6, 6-4",
            MatchFacts.resultLine("Arnaldi", "Tiafoe", score(listOf(6, 7, 6, 6, 4), listOf(7, 6, 3, 7, 6)), "away"),
        )
    }

    @Test
    fun `cleanRound handles both the numeric and word forms, stripping the prefix`() {
        // numeric "1/N-finals" form (early rounds)
        assertEquals("Round of 16", MatchFacts.cleanRound("ATP French Open - 1/8-finals"))
        assertEquals("Round of 32", MatchFacts.cleanRound("Birmingham - 1/16-finals"))
        assertEquals("Quarterfinal", MatchFacts.cleanRound("Madrid - 1/4-finals"))
        assertEquals("Semifinal", MatchFacts.cleanRound("Rome - 1/2-finals"))
        // word form (late rounds) — the case that was being dropped
        assertEquals("Quarterfinal", MatchFacts.cleanRound("WTA French Open - Quarter-finals"))
        assertEquals("Semifinal", MatchFacts.cleanRound("French Open - Semi-finals"))
        assertEquals("Final", MatchFacts.cleanRound("Wimbledon - Final"))
        assertEquals("Round of 16", MatchFacts.cleanRound("US Open - Round of 16"))
        assertEquals("Qualification", MatchFacts.cleanRound("French Open - Qualification"))
        assertNull(MatchFacts.cleanRound("Some - Weird Round"))
        assertNull(MatchFacts.cleanRound(null))
    }
}
