package com.tenniscompanion.api

import java.time.Instant

/** Player side of a live match. `playerId` is the canonical Sackmann id (null until reconciled). */
data class PlayerSideDto(
    val name: String,
    val playerId: Long?,
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
    val player1: PlayerSideDto,
    val player2: PlayerSideDto,
    val score: Map<String, Any?>?,
    val startTime: Instant?,
)

data class RankingRowDto(
    val rank: Int,
    val playerId: Long?,
    val name: String,
    val country: String?,
    val points: Int?,
)
