package com.tenniscompanion.match

import com.tenniscompanion.integration.NormalizedGame
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tanh

/** One sample on the momentum line. `y` is signed (+ = player 1, − = player 2), saturating at ±1. */
data class MomentumPoint(
    val x: Int,            // points played so far (the x-axis)
    val y: Double,         // momentum after this point, tanh-squashed into (-1, 1)
    val sets: String,      // completed-set games, e.g. "6-3"
    val games: String,     // current-set games, e.g. "4-3" (player1-player2)
    val points: String,    // in-game point score, e.g. "30-15" ("" on a game-ending sample)
    val server: Int,       // who is serving this game: 1 | 2 (0 on the pre-match origin sample)
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
 *  • a per-game impulse weighted by game INTENSITY (deuces, long games, pressure points), by SET PROGRESS
 *    (later games in a set matter more) and a STREAK multiplier so consecutive holds/breaks compound;
 *  • MATCH-STATE LEVERAGE — every impulse scales by how much the current set matters, i.e. how much
 *    winning vs losing it swings the match-win probability (a 2nd-set tiebreak is huge in best-of-3,
 *    less so in best-of-5; a deciding set is everything). The remaining-set odds blend a skill prior
 *    (rank gap) with the momentum entering the set, bounded and self-damping;
 *  • a discrete SET-WON impulse at each set boundary, sized by the actual swing in match-win probability
 *    — so winning a tight tiebreak set counts by its STAKES, not its slim game margin;
 *  • a STAMINA / fresh-set regression toward neutral at each set boundary, so earlier-set dominance fades
 *    as the match wears on (winning set 1 means little by a tense set-2 finish);
 *  • an extra shock on a break of serve; passive per-point decay; and a tanh squash so momentum saturates.
 *
 * The constants were tuned against real matches (see MomentumCalculatorTest). x is "points played" — the
 * feed has no per-point timestamps, so points are the time proxy.
 */
object MomentumCalculator {

    private val DECAY = exp(-0.005)         // ~0.5% bleed per counted point toward neutral
    private const val GAME_BASE = 0.05      // base within-set game-win impulse (the set-win impulse below
                                            // now carries the bulk of the set-level signal, so this is gentler)
    private const val BREAK_SHOCK = 0.035   // extra punch when serve is broken (× leverage weight)

    // A tiebreak is a "mini-set": each point acts like a small game so the line zig-zags, with point
    // streaks (3+ in a row) and mini-breaks (a point won against serve) carrying real weight — scaled
    // well below a full game/break so the whole tiebreak swings less than an ordinary set.
    private const val TB_POINT_BASE = 0.035
    private const val TB_MINIBREAK_SHOCK = 0.028
    private const val TB_STREAK_GAIN = 0.45    // a run of tiebreak points compounds hard (mini-set velocity)

    // Match-state leverage: every impulse scales by the CURRENT set's importance (how much winning vs
    // losing it swings the match-win probability), mapped to a weight ≈ the old 1.0–1.8 match-progress range.
    private const val LEV_BASE = 0.9
    private const val LEV_GAIN = 0.9

    // Remaining-set odds q (prob player 1 wins a generic remaining set) — a bounded blend of a skill prior
    // (rank gap) and the momentum entering the set. Self-damping: more momentum → more lopsided q → LOWER
    // leverage (a near-decided set matters less), so the coupling can't run away.
    private const val K_SKILL = 0.6
    private const val K_MOM = 0.18
    private const val MIN_Q = 0.25
    private const val MAX_Q = 0.75
    private const val SKILL_SLOPE = 0.5
    private const val SKILL_MIN = 0.40
    private const val SKILL_MAX = 0.60

    // Set boundary: a stamina / fresh-set regression toward neutral (earlier dominance fades as the match
    // wears on), THEN a discrete set-won impulse sized by the actual swing in match-win probability (ΔP).
    // A lateness factor makes a deep decider (e.g. a 4th-set TB) outweigh an equally-important earlier set.
    private const val SET_RESET = 0.55
    private const val SET_BASE = 2.55
    private const val LATE_GAIN = 0.12

    fun compute(games: List<NormalizedGame>, bestOf: Int, rank1: Int? = null, rank2: Int? = null): MomentumResult {
        val setsToWin = (bestOf + 1) / 2
        val skill = skillPrior(rank1, rank2)
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
        var setsWon1 = 0
        var setsWon2 = 0
        var qCur = 0.5  // remaining-set odds for player 1, recomputed at each set start
        var wLev = 1.0  // current set's leverage weight

        fun setsStr() = completed.joinToString(", ") { "${it.first}-${it.second}" }
        fun push(pts: String, server: Int, swing: Boolean = true) {
            val y = tanh(rawM)
            if (swing && series.isNotEmpty()) {
                val d = y - series.last().y
                if (kotlin.math.abs(d) > biggestSwing) { biggestSwing = kotlin.math.abs(d); swingSide = if (d >= 0) 1 else 2; swingX = idx }
            }
            series.add(MomentumPoint(idx, round(y), setsStr(), "$gP1-$gP2", pts, server))
        }

        // P(player 1 wins the match) from a sets-won state, each remaining set won by player 1 with prob qCur.
        fun winProb(a: Int, b: Int): Double = when {
            a >= setsToWin -> 1.0
            b >= setsToWin -> 0.0
            else -> qCur * winProb(a + 1, b) + (1.0 - qCur) * winProb(a, b + 1)
        }

        // At the start of each set: blend skill + entering-momentum into the remaining-set odds, then
        // derive this set's leverage weight from how much it swings the match-win probability.
        fun beginSet() {
            qCur = (0.5 + K_SKILL * (skill - 0.5) + K_MOM * tanh(rawM)).coerceIn(MIN_Q, MAX_Q)
            val importance = winProb(setsWon1 + 1, setsWon2) - winProb(setsWon1, setsWon2 + 1)
            wLev = LEV_BASE + LEV_GAIN * importance
        }

        // At each set boundary: regress toward neutral (stamina / fresh set), then apply a set-won impulse
        // sized by the actual swing in match-win probability — already signed toward the set winner.
        fun closeSet() {
            val pre = winProb(setsWon1, setsWon2)
            if (gP1 > gP2) setsWon1++ else setsWon2++
            val post = winProb(setsWon1, setsWon2)
            val lateness = 1.0 + LATE_GAIN * (curSet - 1)
            rawM = rawM * SET_RESET + (post - pre) * SET_BASE * lateness
            sets.add(MomentumSet("Set $curSet", "$gP1-$gP2", setStartIdx, idx))
            completed.add(gP1 to gP2)
            gP1 = 0; gP2 = 0; setStartIdx = idx
            push("", 0, swing = false) // boundary sample showing the set-win jump (not a point swing)
        }

        series.add(MomentumPoint(0, 0.0, "", "0-0", "", 0))

        for (g in games) {
            val gameStartIdx = idx // point index at the start of this game (for event highlighting)
            if (g.setNumber != curSet) {
                // normally the prior set closed via setDone below; this is a fallback for a set-number
                // jump where it didn't (e.g. an incomplete prior set in sparse data) so its bracket isn't lost
                if (curSet != 0 && (gP1 > 0 || gP2 > 0)) closeSet()
                curSet = g.setNumber
                beginSet()
            }

            if (g.isTiebreak) {
                var tbOwner = 0
                var tbCount = 0
                for (p in g.points) {
                    val pw = p.winnerSide ?: continue
                    val sign = if (pw == 1) 1 else -1
                    if (tbOwner == sign) tbCount++ else { tbOwner = sign; tbCount = 1 }
                    val streakMult = 1.0 + TB_STREAK_GAIN * maxOf(0, tbCount - 1).toDouble().pow(1.25)
                    var impulse = sign * TB_POINT_BASE * wLev * streakMult
                    if (p.server != null && p.server != pw) impulse += sign * TB_MINIBREAK_SHOCK * wLev // mini-break
                    rawM = rawM * DECAY + impulse
                    idx++
                    push(p.label, p.server ?: g.serverSide)
                }
                // the tiebreak wins the set's deciding game; count it as one game-win for the streak meta
                val sign = if (g.winnerSide == 1) 1 else -1
                if (streakOwner == sign) streakCount++ else { streakOwner = sign; streakCount = 1; streakStart = gameStartIdx }
                if (streakCount > largestStreak) { largestStreak = streakCount; streakSide = if (sign == 1) 1 else 2; lsStart = streakStart; lsEnd = idx }
                if (g.winnerSide == 1) gP1++ else gP2++
                idx++
                push("", g.serverSide)
            } else {
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
                    push(p.label, g.serverSide)
                }

                val sign = if (g.winnerSide == 1) 1 else -1
                if (streakOwner == sign) streakCount++ else { streakOwner = sign; streakCount = 1; streakStart = gameStartIdx }
                val newStreakRecord = streakCount > largestStreak
                if (newStreakRecord) { largestStreak = streakCount; streakSide = if (sign == 1) 1 else 2; lsStart = streakStart }
                val streakMult = 1.0 + 0.30 * maxOf(0, streakCount - 1).toDouble().pow(1.4)
                val wSet = setWeight(g.gameInSet)
                val impulse = sign * GAME_BASE * intensity * wSet * wLev * streakMult
                rawM = rawM * DECAY + impulse

                if (g.winnerSide == 1) gP1++ else gP2++
                idx++
                if (newStreakRecord) lsEnd = idx
                if (heaviestPending) { heaviestEnd = idx; heaviestPending = false }
                val isBreak = g.winnerSide != g.serverSide
                if (isBreak) {
                    rawM += sign * BREAK_SHOCK * wLev
                    breaks.add(MomentumBreak(idx, round(tanh(rawM)), if (sign == 1) 1 else 2))
                }
                push("", g.serverSide)
            }

            if (setDone(gP1, gP2)) closeSet()
        }
        if (gP1 > 0 || gP2 > 0) sets.add(MomentumSet("Set $curSet", "$gP1-$gP2", setStartIdx, idx))

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

    /** Player 1's prior probability of winning a generic set, from the rank gap. Bounded so it only
     *  nudges (skill informs the stakes; the match itself drives momentum). Neutral 0.5 if ranks unknown. */
    private fun skillPrior(r1: Int?, r2: Int?): Double {
        if (r1 == null || r2 == null || r1 <= 0 || r2 <= 0) return 0.5
        val diff = ln(r2.toDouble()) - ln(r1.toDouble()) // > 0 when player 1 is higher-ranked (lower number)
        return (1.0 / (1.0 + exp(-SKILL_SLOPE * diff))).coerceIn(SKILL_MIN, SKILL_MAX)
    }

    /** A set is decided at 6 games clear by 2, or at 7 (7-5 or a 7-6 tiebreak). */
    private fun setDone(g1: Int, g2: Int): Boolean {
        val hi = maxOf(g1, g2)
        return hi == 7 || (hi >= 6 && hi - minOf(g1, g2) >= 2)
    }

    private fun round(d: Double) = Math.round(d * 10000.0) / 10000.0
}
