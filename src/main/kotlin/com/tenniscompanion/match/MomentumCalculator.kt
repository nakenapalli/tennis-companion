package com.tenniscompanion.match

import com.tenniscompanion.integration.NormalizedGame
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.tanh

/** One sample on the momentum line. `y` is signed (+ = player 1, − = player 2), saturating at ±1. */
data class MomentumPoint(
    val x: Int,            // points played so far (the x-axis)
    val y: Double,         // momentum after this point, tanh-squashed into (-1, 1)
    val sets: String,      // completed-set games, e.g. "6-3"
    val games: String,     // current-set games, e.g. "4-3" (player1-player2)
    val points: String,    // in-game point score, e.g. "30-15" ("" on a game-ending sample)
)

/** A break of serve, placed on the line for a marker. `by` is 1 (player 1 broke) or 2. */
data class MomentumBreak(val x: Int, val y: Double, val by: Int)

/** One set's span on the x-axis, for the brackets under the chart. */
data class MomentumSet(val label: String, val score: String, val startX: Int, val endX: Int)

data class MomentumMeta(
    val largestStreak: Int,
    val streakSide: Int,        // who owns the largest game-winning streak
    val streakStartX: Int,      // x-range (points) the streak spans — for highlighting on hover
    val streakEndX: Int,
    val heaviestGame: String,   // "Set 2, game 6" — the most intense game
    val heaviestStartX: Int,    // x-range of that game
    val heaviestEndX: Int,
    val biggestSwing: Double,   // largest single-point momentum jump (absolute)
    val swingSide: Int,         // toward whom the biggest swing went
    val swingX: Int,            // the point where the biggest swing landed
)

data class MomentumResult(
    val series: List<MomentumPoint>,
    val breaks: List<MomentumBreak>,
    val sets: List<MomentumSet>,
    val meta: MomentumMeta,
)

/**
 * Turns a match's reconstructed games into a signed "momentum" line. This is a bespoke model, not a
 * standard stat — it blends several forces so the curve reads like the feel of the match:
 *
 *  • point micro-impulses — small nudges per point, larger on break/set/match points;
 *  • a per-game impulse weighted by game INTENSITY (deuces, long games, pressure points carry more
 *    emotional weight than a love hold), by SET PROGRESS (later games in a set matter more) and MATCH
 *    PROGRESS (later sets are heavier), and by a STREAK multiplier so consecutive holds/breaks compound
 *    into a near-exponential run (velocity);
 *  • an extra shock on a break of serve;
 *  • passive decay each point so stale momentum bleeds back toward neutral;
 *  • a tanh squash so momentum saturates near the extremes (diminishing returns when already dominant).
 *
 * The constants were tuned against real matches (see MomentumCalculatorTest). x is "points played" — the
 * feed has no per-point timestamps, so points are the time proxy.
 */
object MomentumCalculator {

    private val DECAY = exp(-0.005)         // ~0.5% bleed per counted point toward neutral
    private const val GAME_BASE = 0.08      // base size of a game-win impulse (tuned down from 0.10 so the
                                            // line saturates less and stays responsive to recent games)
    private const val BREAK_SHOCK = 0.05    // extra punch when serve is broken (× match-progress weight)
    private val W_MATCH = mapOf(            // match-progress weight per set index (0-based)
        3 to doubleArrayOf(1.0, 1.3, 1.6),
        5 to doubleArrayOf(1.0, 1.15, 1.3, 1.5, 1.8),
    )

    fun compute(games: List<NormalizedGame>, bestOf: Int): MomentumResult {
        val wMatch = W_MATCH[bestOf] ?: W_MATCH[3]!!
        val series = ArrayList<MomentumPoint>()
        val breaks = ArrayList<MomentumBreak>()
        val sets = ArrayList<MomentumSet>()

        var rawM = 0.0
        var idx = 0
        var streakOwner = 0
        var streakCount = 0
        var largestStreak = 0
        var streakSide = 0
        var streakStart = 0
        var lsStart = 0
        var lsEnd = 0
        var heaviestI = 0.0
        var heaviestLabel = ""
        var heaviestStart = 0
        var heaviestEnd = 0
        var heaviestPending = false
        var biggestSwing = 0.0
        var swingSide = 0
        var swingX = 0

        val completed = ArrayList<Pair<Int, Int>>()
        var gP1 = 0
        var gP2 = 0
        var curSet = 0
        var setStartIdx = 0

        fun setsStr() = completed.joinToString(", ") { "${it.first}-${it.second}" }
        fun push(pts: String) {
            val y = tanh(rawM)
            if (series.isNotEmpty()) {
                val d = y - series.last().y
                if (kotlin.math.abs(d) > biggestSwing) { biggestSwing = kotlin.math.abs(d); swingSide = if (d >= 0) 1 else 2; swingX = idx }
            }
            series.add(MomentumPoint(idx, round(y), setsStr(), "$gP1-$gP2", pts))
        }

        series.add(MomentumPoint(0, 0.0, "", "0-0", ""))

        for (g in games) {
            val gameStartIdx = idx // point index at the start of this game (for event highlighting)
            if (g.setNumber != curSet) {
                if (curSet != 0) {
                    sets.add(MomentumSet("Set $curSet", "$gP1-$gP2", setStartIdx, idx))
                    completed.add(gP1 to gP2)
                    gP1 = 0; gP2 = 0; setStartIdx = idx
                }
                curSet = g.setNumber
            }

            val pts = g.points
            val pointsPlayed = pts.size + 1 // the listed points + the omitted deciding point
            var deuce = 0
            var prevLabel = ""
            val bp = pts.count { it.breakPoint }
            val sp = pts.count { it.setPoint }
            val mp = pts.count { it.matchPoint }
            for (p in pts) {
                if (p.label == "40-40" && prevLabel.contains("A")) deuce++
                prevLabel = p.label
            }
            val intensity = minOf(
                4.0,
                1.0 + 0.15 * maxOf(0, pointsPlayed - 4) + 0.30 * deuce + 0.50 * bp + 0.80 * sp + 1.50 * mp,
            )
            if (intensity > heaviestI) {
                heaviestI = intensity; heaviestLabel = "Set ${g.setNumber}, game ${g.gameInSet}"
                heaviestStart = gameStartIdx; heaviestPending = true
            }

            for (p in pts) {
                val pw = when (p.winnerSide) { 1 -> 1; 2 -> -1; else -> 0 }
                if (pw == 0) continue
                var micro = 0.004 * pw
                if (p.breakPoint) micro += pw * 0.012
                if (p.setPoint) micro += pw * 0.020
                if (p.matchPoint) micro += pw * 0.035
                rawM = rawM * DECAY + micro
                idx++
                push(p.label)
            }

            val sign = if (g.winnerSide == 1) 1 else -1
            if (streakOwner == sign) streakCount++ else { streakOwner = sign; streakCount = 1; streakStart = gameStartIdx }
            val newStreakRecord = streakCount > largestStreak
            if (newStreakRecord) { largestStreak = streakCount; streakSide = if (sign == 1) 1 else 2; lsStart = streakStart }
            val streakMult = 1.0 + 0.30 * maxOf(0, streakCount - 1).toDouble().pow(1.4)
            val wSet = setWeight(g.gameInSet)
            val wMass = wMatch.getOrElse(curSet - 1) { wMatch.last() }
            val impulse = sign * GAME_BASE * intensity * wSet * wMass * streakMult
            rawM = rawM * DECAY + impulse

            if (g.winnerSide == 1) gP1++ else gP2++
            idx++
            if (newStreakRecord) lsEnd = idx
            if (heaviestPending) { heaviestEnd = idx; heaviestPending = false }
            val isBreak = g.winnerSide != g.serverSide
            if (isBreak) {
                rawM += sign * BREAK_SHOCK * wMass
                breaks.add(MomentumBreak(idx, round(tanh(rawM)), if (sign == 1) 1 else 2))
            }
            push("")
        }
        if (curSet != 0) sets.add(MomentumSet("Set $curSet", "$gP1-$gP2", setStartIdx, idx))

        return MomentumResult(
            series, breaks, sets,
            MomentumMeta(
                largestStreak, streakSide, lsStart, lsEnd,
                heaviestLabel, heaviestStart, heaviestEnd,
                round(biggestSwing), swingSide, swingX,
            ),
        )
    }

    /**
     * Set-progress weight by game number — a smooth logistic S-curve (not linear): stays gentle through
     * the opening games, accelerates through the middle of the set, then eases toward a 1.5 ceiling for
     * the closing/tiebreak games. Approximate per-game values: g1–4 ≈ 0.71–0.75, g5–8 ≈ 0.79–1.04,
     * g9–10 ≈ 1.16–1.27, g11+ ≈ 1.35 → 1.5.
     */
    private fun setWeight(gameInSet: Int): Double =
        (0.7 + 0.8 / (1.0 + exp(-0.6 * (gameInSet - 8.5)))).coerceAtMost(1.5)

    private fun round(d: Double) = Math.round(d * 10000.0) / 10000.0
}
