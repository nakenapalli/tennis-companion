package com.tenniscompanion.integration

import com.tenniscompanion.reconcile.NameNormalizer
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue

/**
 * Importance tier of a tournament. Weights are spaced so the round bonus (0–50) can never lift a match
 * across a tier boundary — a Grand Slam first-rounder still outranks a 250 final.
 */
enum class TournamentTier(val weight: Int) {
    GRAND_SLAM(1000),
    FINALS(900),       // ATP / WTA Tour Finals
    MASTERS_1000(800), // ATP Masters 1000 / WTA 1000
    TOUR_500(600),
    TOUR_250(400),
    CHALLENGER(200),
    ITF(100),
    JUNIOR(60),
    OTHER(50);

    companion object {
        /** Map a curated-file value (tour-specific names allowed) onto a tour-agnostic tier. */
        fun fromKey(raw: String): TournamentTier? = when (raw.trim().uppercase()) {
            "GRAND_SLAM", "SLAM", "G" -> GRAND_SLAM
            "FINALS", "ATP_FINALS", "WTA_FINALS", "TOUR_FINALS", "F" -> FINALS
            "MASTERS_1000", "ATP_1000", "WTA_1000", "1000", "M" -> MASTERS_1000
            "ATP_500", "WTA_500", "500" -> TOUR_500
            "ATP_250", "WTA_250", "250", "TOUR" -> TOUR_250
            "CHALLENGER", "C" -> CHALLENGER
            "ITF" -> ITF
            "JUNIOR" -> JUNIOR
            else -> null
        }
    }
}

/**
 * Resolves a tournament to a [TournamentTier], using a curated name→tier map (`tournament-tiers.json`)
 * because the feed exposes no slam marker or 250-vs-500 size. Names are matched accent/case/punctuation-
 * insensitively via [NameNormalizer.fold]. Juniors are classified first so a junior event at a slam isn't
 * read as Grand Slam; anything not in the map falls back to the feed's coarse `category`.
 */
@Component
class TournamentTierRegistry(mapper: ObjectMapper) {

    private val tiers: Map<String, TournamentTier> = load(mapper)

    fun tierOf(tournamentName: String?, category: String?): TournamentTier {
        if (isJunior(category, tournamentName)) return TournamentTier.JUNIOR
        tournamentName?.let { tiers[NameNormalizer.fold(it)] }?.let { return it }
        return fromCategory(category)
    }

    private fun isJunior(category: String?, name: String?): Boolean {
        if (category.equals("Junior", ignoreCase = true)) return true
        val n = name?.lowercase() ?: return false
        return n.contains("junior") || n.contains("boys") || n.contains("girls")
    }

    private fun fromCategory(category: String?): TournamentTier = when {
        category == null -> TournamentTier.OTHER
        category.contains("Grand Slam", true) -> TournamentTier.GRAND_SLAM
        category.contains("Challenger", true) -> TournamentTier.CHALLENGER
        category.contains("ITF", true) -> TournamentTier.ITF
        category.contains("Junior", true) -> TournamentTier.JUNIOR
        category.contains("ATP", true) || category.contains("WTA", true) -> TournamentTier.TOUR_250
        else -> TournamentTier.OTHER
    }

    private fun load(mapper: ObjectMapper): Map<String, TournamentTier> = runCatching {
        val res = ClassPathResource("tournament-tiers.json")
        if (!res.exists()) return emptyMap()
        res.inputStream.use { mapper.readValue<Map<String, String>>(it) }
            .filterKeys { !it.startsWith("_") }
            .mapNotNull { (name, tier) -> TournamentTier.fromKey(tier)?.let { NameNormalizer.fold(name) to it } }
            .toMap()
    }.getOrElse {
        LoggerFactory.getLogger(javaClass).warn("Could not load tournament-tiers.json: {}", it.message)
        emptyMap()
    }
}

/**
 * Computes a match's importance weight = tournament tier + a round bonus. Used to order the served match
 * lists so the most important play (a Grand Slam final) surfaces first regardless of when it started.
 */
@Component
class MatchWeighting(private val tiers: TournamentTierRegistry) {

    fun weight(tournamentName: String?, category: String?, round: String?): Int =
        tiers.tierOf(tournamentName, category).weight + roundBonus(round)

    /** The resolved tier (for the UI badge). */
    fun tierOf(tournamentName: String?, category: String?): TournamentTier = tiers.tierOf(tournamentName, category)

    /** The feed encodes round as "WTA French Open - Final" — take the part after the last " - ". */
    fun roundBonus(round: String?): Int {
        val r = round?.substringAfterLast(" - ")?.lowercase()?.trim() ?: return 0
        return when {
            r.contains("semi") || r.contains("1/2") -> 40
            r.contains("quarter") || r.contains("1/4") -> 30
            r.contains("round of 16") || r.contains("last 16") || r.contains("1/8") -> 20
            r.contains("final") -> 50 // checked after semi/quarter, which also contain "final"
            else -> 0
        }
    }
}
