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
