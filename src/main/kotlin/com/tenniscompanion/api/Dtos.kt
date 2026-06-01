package com.tenniscompanion.api

import java.time.LocalDate

/** Response shapes for the player endpoints. Plain Kotlin data classes → JSON via Jackson. */

data class PlayerProfileDto(
    val playerId: Long,
    val firstName: String?,
    val lastName: String?,
    val tour: String,
    val country: String?,
    val hand: String?,
    val heightCm: Int?,
    val birthDate: LocalDate?,
    val currentRank: Int?,
    val currentRankDate: LocalDate?,
)

/** A match from the subject player's perspective (result is W/L for *them*, opponent is the other). */
data class MatchDto(
    val tourneyName: String?,
    val tourneyDate: LocalDate?,
    val surface: String?,
    val round: String?,
    val result: String,
    val opponentId: Long?,
    val opponentName: String?,
    val score: String?,
)

data class H2hDto(
    val playerId: Long,
    val opponentId: Long,
    val playerWins: Int,
    val opponentWins: Int,
    val matches: List<MatchDto>,
)
