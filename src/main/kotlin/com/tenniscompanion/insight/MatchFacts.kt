package com.tenniscompanion.insight

/**
 * Pure helpers that turn a stored score map + raw round string into unambiguous, model-ready facts —
 * so the digest LLM never has to infer who won, reconstruct the set order, or translate a cryptic round
 * code (the two accuracy bugs we saw). Kept out of FactSheetBuilder so they're unit-testable without a DB.
 *
 * The score map is the shape we persist/cache: { "home": { "sets": [Int,…] }, "away": { "sets": [Int,…] } }.
 */
object MatchFacts {

    /** "home" / "away" / null — which side won more completed sets. null if it can't be determined. */
    fun winnerOf(score: Map<String, Any?>?): String? {
        val (home, away) = sets(score) ?: return null
        var h = 0
        var a = 0
        home.zip(away).forEach { (hg, ag) -> if (hg > ag) h++ else if (ag > hg) a++ }
        return when {
            h > a -> "home"
            a > h -> "away"
            else -> null
        }
    }

    /** "<winner> beats <loser> 6-3, 4-6, 6-0" — full score from the winner's perspective. */
    fun resultLine(winnerName: String, loserName: String, score: Map<String, Any?>?, winnerSide: String): String {
        val s = scoreFrom(score, winnerSide)
        return "$winnerName beats $loserName" + if (s.isNotBlank()) " $s" else ""
    }

    /** Comma-separated per-set games from one side's perspective: side="home" → "6-3, 4-6, 6-0". */
    fun scoreFrom(score: Map<String, Any?>?, side: String): String {
        val (home, away) = sets(score) ?: return ""
        val (mine, theirs) = if (side == "away") away to home else home to away
        return mine.zip(theirs).joinToString(", ") { (m, t) -> "$m-$t" }
    }

    @Suppress("UNCHECKED_CAST")
    private fun sets(score: Map<String, Any?>?): Pair<List<Int>, List<Int>>? {
        if (score == null) return null
        fun side(key: String): List<Int>? =
            ((score[key] as? Map<String, Any?>)?.get("sets") as? List<Any?>)?.mapNotNull { (it as? Number)?.toInt() }
        val home = side("home") ?: return null
        val away = side("away") ?: return null
        if (home.isEmpty() || home.size != away.size) return null
        return home to away
    }

    private val ROUND_BY_DENOMINATOR = mapOf(
        2 to "Semifinal", 4 to "Quarterfinal", 8 to "Round of 16",
        16 to "Round of 32", 32 to "Round of 64", 64 to "Round of 128",
    )

    /**
     * Clean the upstream round string into a plain English stage. api-tennis is inconsistent: early
     * rounds come as "<tournament> - 1/N-finals" (e.g. "French Open - 1/8-finals" = Round of 16) while
     * late rounds use word forms ("Quarter-finals", "Semi-finals", "Final"). Strips the "<name> - "
     * prefix, normalizes both forms, passes through Qualification, and returns null if unrecognized —
     * so an unknown round is simply omitted rather than guessed at.
     */
    fun cleanRound(raw: String?): String? {
        val s = raw?.substringAfterLast(" - ")?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        val compact = s.replace("-", "").replace(" ", "")
        return when {
            compact == "final" -> "Final"
            s.contains("qualif") -> "Qualification"
            compact.startsWith("quarterfinal") -> "Quarterfinal"
            compact.startsWith("semifinal") -> "Semifinal"
            s.startsWith("round of ") -> "Round of ${s.substringAfterLast(' ')}"
            s.startsWith("1/") -> ROUND_BY_DENOMINATOR[s.removePrefix("1/").takeWhile { it.isDigit() }.toIntOrNull()]
            else -> null
        }
    }
}
