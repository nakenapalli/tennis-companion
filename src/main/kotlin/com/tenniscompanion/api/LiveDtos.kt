package com.tenniscompanion.api

import java.time.Instant
import java.util.UUID

/** Player side of a live match. `playerId` is the canonical UUID (null until reconciled). */
data class PlayerSideDto(
    val name: String,
    val playerId: UUID?,
    val country: String?,
    val rank: Int?,
)

data class LiveMatchDto(
    val externalId: String,
    val status: String,
    val tournamentName: String?,
    val round: String?,
    val surface: String?,
    val tour: String?,
    val category: String?, // circuit (ATP/WTA/Challenger/ITF/...) — the UI filters to main tour by default
    val qualifying: Boolean = false, // qualifying-draw match — no round bonus, ranks below main draw, labeled in the UI
    val player1: PlayerSideDto,
    val player2: PlayerSideDto,
    val score: Map<String, Any?>?,
    val startTime: Instant?,
    val tier: String? = null, // TournamentTier name (GRAND_SLAM/MASTERS_1000/…); stamped on read for the UI badge
    val tournamentId: Long? = null, // canonical tournaments.id, name-matched on read — lets the UI link a match to its tournament
    val serve: String? = null, // "home" | "away" — who is serving (live only)
    val endedAt: Instant? = null, // approx finish time (last poll) — only set on the match-detail endpoint
)

data class RankingRowDto(
    val rank: Int,
    val playerId: UUID?,
    val name: String,
    val country: String?,
    val points: Int?,
)
