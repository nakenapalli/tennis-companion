package com.tenniscompanion.match

import com.tenniscompanion.integration.NormalizedGame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MomentumCalculatorTest {

    /** A game with no listed points: outcome only (server/winner). */
    private fun game(setNo: Int, gameInSet: Int, server: Int, winner: Int) =
        NormalizedGame(setNo, gameInSet, server, winner, emptyList())

    @Test
    fun `series starts neutral`() {
        val r = MomentumCalculator.compute(listOf(game(1, 1, 2, 1)), bestOf = 3)
        assertEquals(0.0, r.series.first().y)
        assertEquals("0-0", r.series.first().games)
    }

    @Test
    fun `a one-sided run drives momentum strongly to that player and compounds`() {
        // Player 1 wins six straight games (a bagel set), alternating server so half are breaks.
        val games = (1..6).map { game(1, it, server = if (it % 2 == 0) 1 else 2, winner = 1) }
        val r = MomentumCalculator.compute(games, bestOf = 3)

        assertTrue(r.series.last().y > 0.7) { "expected dominant +momentum, got ${r.series.last().y}" }
        // Streak multiplier => later games move the line more than the first (acceleration / velocity).
        val firstJump = r.series[1].y - r.series[0].y
        val lastJump = r.series.last().y - r.series[r.series.size - 2].y
        assertTrue(lastJump > firstJump) { "expected accelerating jumps: first=$firstJump last=$lastJump" }
        assertEquals(6, r.meta.largestStreak)
        assertTrue(r.breaks.size == 3) { "expected 3 breaks, got ${r.breaks.size}" }
    }

    @Test
    fun `the other player's games push momentum negative`() {
        val games = (1..4).map { game(1, it, server = 1, winner = 2) } // player 2 breaks repeatedly
        val r = MomentumCalculator.compute(games, bestOf = 3)
        assertTrue(r.series.last().y < -0.3) { "expected negative momentum, got ${r.series.last().y}" }
        assertEquals(2, r.meta.streakSide)
    }

    @Test
    fun `set brackets span the match`() {
        val games = listOf(game(1, 1, 1, 1), game(1, 2, 2, 1), game(2, 1, 1, 2))
        val r = MomentumCalculator.compute(games, bestOf = 3)
        assertEquals(2, r.sets.size)
        assertEquals(0, r.sets.first().startX)
        assertEquals("Set 1", r.sets.first().label)
    }
}
