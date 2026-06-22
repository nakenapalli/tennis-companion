package com.tenniscompanion.integration

import java.time.Instant
import java.time.LocalDate

/**
 * Provider-agnostic types the upstream adapter emits. The rest of the app depends only on these —
 * never on a provider's raw response shape — so swapping providers means rewriting one adapter
 * (design §6.1). `externalId` + the distinguishing fields (country, rankHint) are what the
 * reconciliation engine uses to map an upstream player onto a canonical Sackmann player.
 */
data class NormalizedPlayerRef(
    val externalId: String,
    val name: String,
    val tour: String, // "ATP" | "WTA"
    val countryCode: String? = null,
    val rankHint: Int? = null,
)

data class NormalizedMatch(
    val externalId: String,
    val status: String, // scheduled | live | finished
    val tournamentExternalId: String? = null,
    val tournamentName: String? = null,
    val round: String? = null,
    val surface: String? = null,
    val tour: String? = null,
    val category: String? = null, // circuit: ATP | WTA | Challenger | ITF | ... (for main-tour filtering)
    val qualifying: Boolean = false, // a qualifying-draw match (feed reuses main-draw round names for these)
    val player1: NormalizedPlayerRef,
    val player2: NormalizedPlayerRef,
    val score: Map<String, Any?>? = null,
    val startTime: Instant? = null,
    val serve: String? = null, // "home" | "away" — who is serving (live only); null otherwise
)

data class NormalizedRanking(
    val tour: String,
    val rank: Int,
    val player: NormalizedPlayerRef,
    val points: Int? = null,
)

/**
 * Minimal upstream player profile used only by the admin reconciliation review to disambiguate
 * namesakes (not part of the serving path). All nullable — the feed may supply none of it.
 */
data class UpstreamPlayerProfile(
    val country: String? = null, // IOC code
    val birthYear: Int? = null,
    val rank: Int? = null,
)

/**
 * Provider-agnostic match detail used by the momentum + stats tabs: the reconstructed game/point flow
 * and the per-period statistics, both with players resolved to side 1 (first) / side 2 (second).
 */
data class NormalizedMatchDetail(
    val games: List<NormalizedGame>,
    val statistics: List<NormalizedStat>,
    val player1Key: String? = null, // upstream player keys (for on-demand H2H / career lookups)
    val player2Key: String? = null,
)

/** One prior meeting, normalized so side 1 = the first key passed to [TennisApiAdapter.fetchH2H]. */
data class NormalizedH2HMatch(
    val date: String?,
    val tournament: String?,
    val round: String?,
    val winnerSide: Int,   // 1 | 2 (which of the two queried players won)
    val score: String?,    // games per set from side 1's perspective, e.g. "6-4, 4-6, 6-3"
)

/** A player's latest-season singles career line (get_players), for the bios tab. */
data class NormalizedPlayerCareer(
    val country: String? = null, // IOC
    val birthYear: Int? = null,
    val rank: Int? = null,
    val logo: String? = null,
    val season: String? = null,
    val titles: Int? = null,
    val wins: Int? = null,
    val losses: Int? = null,
    val hardWins: Int? = null,
    val hardLosses: Int? = null,
    val clayWins: Int? = null,
    val clayLosses: Int? = null,
    val grassWins: Int? = null,
    val grassLosses: Int? = null,
)

/** A single game. `serverSide`/`winnerSide` are 1|2; `points` are the LISTED points (deciding point omitted). */
data class NormalizedGame(
    val setNumber: Int,   // 1-based
    val gameInSet: Int,   // 1-based, resets each set
    val serverSide: Int,  // 1 | 2
    val winnerSide: Int,  // 1 | 2
    val points: List<NormalizedGamePoint>,
)

/** One point. `winnerSide` is who won it (null if undetermined); `label` is the in-game score "30-15" (side1-side2). */
data class NormalizedGamePoint(
    val winnerSide: Int?,
    val label: String,
    val breakPoint: Boolean,
    val setPoint: Boolean,
    val matchPoint: Boolean,
)

/** One statistic row resolved to a side. `value` is a display string ("60%"); won/total give the ratio. */
data class NormalizedStat(
    val side: Int,      // 1 | 2
    val period: String, // "match" | "set1" | …
    val type: String,   // "Service" | "Return" | "Points" | "Games"
    val name: String,
    val value: String?,
    val won: Int?,
    val total: Int?,
)

data class NormalizedTournament(
    val externalId: String,
    val name: String,
    val level: String? = null,
    val surface: String? = null,
    val location: String? = null,
    val tour: String? = null,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
)
