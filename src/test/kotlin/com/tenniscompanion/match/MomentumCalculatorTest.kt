package com.tenniscompanion.match

import com.tenniscompanion.integration.NormalizedGame
import com.tenniscompanion.integration.NormalizedGamePoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MomentumCalculatorTest {

    /** A game with no listed points: outcome only (server/winner). */
    private fun game(setNo: Int, gameInSet: Int, server: Int, winner: Int) =
        NormalizedGame(setNo, gameInSet, server, winner, emptyList())

    private fun tbPoint(winner: Int, server: Int, label: String) =
        NormalizedGamePoint(winnerSide = winner, label = label, breakPoint = false, setPoint = false, matchPoint = false, server = server)

    /** A full set the given side wins wGames–lGames (games interleaved, outcome only). */
    private fun fullSet(setNo: Int, winner: Int, wGames: Int, lGames: Int): List<NormalizedGame> {
        val loser = if (winner == 1) 2 else 1
        val out = ArrayList<NormalizedGame>()
        var w = 0; var l = 0; var gi = 1
        while (w < wGames || l < lGames) {
            if (w < wGames) { out.add(game(setNo, gi++, server = 1, winner = winner)); w++ }
            if (l < lGames) { out.add(game(setNo, gi++, server = 2, winner = loser)); l++ }
        }
        return out
    }

    private fun MomentumResult.endOfSet(i: Int): Double = series.last { it.x == sets[i].endX }.y

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
    fun `each sample carries the serving side, origin sample has none`() {
        val games = listOf(game(1, 1, server = 2, winner = 1), game(1, 2, server = 1, winner = 1))
        val r = MomentumCalculator.compute(games, bestOf = 3)
        assertEquals(0, r.series.first().server) // pre-match origin sample
        // game-ending samples reflect that game's server (these games have no listed points)
        assertEquals(2, r.series[1].server)
        assertEquals(1, r.series[2].server)
    }

    @Test
    fun `a tiebreak plots every point, zig-zags, and swings toward the dominant player`() {
        // player 1 wins 6 of 7 tiebreak points (incl. mini-breaks on 2's serve); one point goes to player 2
        val tb = NormalizedGame(
            setNumber = 1, gameInSet = 13, serverSide = 1, winnerSide = 1, isTiebreak = true,
            points = listOf(
                tbPoint(1, server = 2, "1-0"), // mini-break
                tbPoint(1, server = 1, "2-0"),
                tbPoint(1, server = 1, "3-0"),
                tbPoint(1, server = 2, "4-0"), // mini-break
                tbPoint(2, server = 2, "4-1"),
                tbPoint(1, server = 1, "5-1"),
                tbPoint(1, server = 2, "6-1"), // mini-break
            ),
        )
        val r = MomentumCalculator.compute(listOf(tb), bestOf = 3)
        val tbSamples = r.series.filter { it.points.isNotEmpty() }
        assertEquals(7, tbSamples.size) // every tiebreak point is its own sample
        assertTrue(r.series.last().y > 0.2) { "expected a clear +swing from the tiebreak, got ${r.series.last().y}" }
        // zig-zag: the point player 2 won ("4-1") dips below the previous sample ("4-0")
        val before = tbSamples.first { it.points == "4-0" }.y
        val dip = tbSamples.first { it.points == "4-1" }.y
        assertTrue(dip < before) { "expected a dip on player 2's point: before=$before dip=$dip" }
        // serve indicator alternates with the actual server of each tiebreak point
        assertEquals(listOf(2, 1, 1, 2, 2, 1, 2), tbSamples.map { it.server })
    }

    @Test
    fun `a tiebreak swings less than a full set of the same one-sidedness`() {
        // one player wins 7 straight games (a set) vs the same player winning 7 straight tiebreak points
        val set = (1..7).map { game(1, it, server = if (it % 2 == 0) 1 else 2, winner = 1) }
        val tb = listOf(
            NormalizedGame(
                1, 13, serverSide = 1, winnerSide = 1, isTiebreak = true,
                points = (1..7).map { tbPoint(1, server = if (it % 2 == 0) 1 else 2, "$it-0") },
            ),
        )
        val setSwing = MomentumCalculator.compute(set, bestOf = 3).series.last().y
        val tbSwing = MomentumCalculator.compute(tb, bestOf = 3).series.last().y
        assertTrue(tbSwing > 0.15) { "tiebreak should still move the line meaningfully, got $tbSwing" }
        assertTrue(tbSwing < setSwing) { "tiebreak should swing less than a full set: tb=$tbSwing set=$setSwing" }
    }

    @Test
    fun `winning a high-leverage set flips momentum despite an earlier dominant set`() {
        // player 1 dominates set 1 (6-1), player 2 takes a tight set 2 (7-6) to level a best-of-3 → decider looms
        val games = fullSet(1, winner = 1, 6, 1) + fullSet(2, winner = 2, 7, 6)
        val r = MomentumCalculator.compute(games, bestOf = 3)
        val afterSet1 = r.endOfSet(0)
        val afterSet2 = r.endOfSet(1)
        assertTrue(afterSet1 > 0.3) { "player 1 should lead after dominating set 1, got $afterSet1" }
        // leveling the match swings momentum hard the other way — past neutral onto player 2's side
        assertTrue(afterSet2 < 0.0) { "leveling the match should put momentum on player 2, got $afterSet2" }
        assertTrue(afterSet1 - afterSet2 > 0.6) { "expected a large set-win swing, got ${afterSet1 - afterSet2}" }
    }

    @Test
    fun `the same 2nd-set turnaround swings more in best-of-3 than best-of-5`() {
        val games = fullSet(1, winner = 1, 6, 1) + fullSet(2, winner = 2, 7, 6)
        fun swing(bestOf: Int): Double {
            val r = MomentumCalculator.compute(games, bestOf)
            return r.endOfSet(0) - r.endOfSet(1) // how far player 2's set-2 win moved the line
        }
        assertTrue(swing(3) > swing(5)) { "best-of-3 2nd set should be more pivotal: bo3=${swing(3)} bo5=${swing(5)}" }
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
