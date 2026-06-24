package com.tenniscompanion.api

import com.tenniscompanion.domain.Match
import com.tenniscompanion.domain.MatchRepository
import com.tenniscompanion.domain.PlayerRepository
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.util.UUID

/**
 * Player-detail reads, all backed by the reconciled Sackmann data (not the live feed): the profile,
 * recent results, and head-to-head. Everything is keyed by the canonical `players.id` UUID — callers
 * resolve a live match's upstream player to that UUID via reconciliation before landing here.
 * Also reused by the match-view's Players/H2H tabs and the digest fact sheet for authoritative names/ranks.
 */
@Service
class PlayerService(
    private val players: PlayerRepository,
    private val matches: MatchRepository,
    private val jdbc: JdbcTemplate,
) {

    /** Bio + current rank, or null if the UUID isn't a known player. */
    fun profile(playerId: UUID): PlayerProfileDto? {
        val p = players.findById(playerId).orElse(null) ?: return null
        val (rank, rankDate) = currentRank(p.id, p.tour)
        return PlayerProfileDto(
            playerId = p.id,
            firstName = p.firstName,
            lastName = p.lastName,
            tour = p.tour,
            country = p.countryCode,
            hand = p.hand,
            heightCm = p.heightCm,
            birthDate = p.birthDate,
            currentRank = rank,
            currentRankDate = rankDate,
        )
    }

    /** Most-recent results first, projected onto this player's W/L perspective. Limit is clamped 1..100. */
    fun recentMatches(playerId: UUID, limit: Int): List<MatchDto> {
        val page = PageRequest.of(0, limit.coerceIn(1, 100), Sort.by(Sort.Direction.DESC, "tourneyDate"))
        return matches.findByWinnerIdOrLoserId(playerId, playerId, page).map { it.toDto(playerId) }
    }

    /** Full head-to-head record + meeting list between two players (used when both are reconciled). */
    fun headToHead(playerId: UUID, opponentId: UUID): H2hDto {
        val all = matches.findHeadToHead(playerId, opponentId)
        val playerWins = all.count { it.winnerId == playerId }
        return H2hDto(
            playerId = playerId,
            opponentId = opponentId,
            playerWins = playerWins,
            opponentWins = all.size - playerWins,
            matches = all.map { it.toDto(playerId) },
        )
    }

    /** Latest ranking row for this player+tour. Checks unified `rankings` table (covers both Sackmann history and API Tennis). */
    private fun currentRank(playerId: UUID, tour: String): Pair<Int?, LocalDate?> {
        val sql = """
            SELECT rank, ranking_date FROM rankings
            WHERE player_id = ?::uuid AND tour = ?
            ORDER BY ranking_date DESC
            LIMIT 1
        """
        return jdbc.query(sql, { rs, _ ->
            (rs.getObject("rank") as? Int) to rs.getDate("ranking_date")?.toLocalDate()
        }, playerId.toString(), tour).firstOrNull() ?: (null to null)
    }

    /** Project a match onto the subject player's perspective. An extension function keeps it tidy. */
    private fun Match.toDto(subjectId: UUID): MatchDto {
        val subjectWon = winnerId == subjectId
        return MatchDto(
            tourneyName = tourneyName,
            tourneyDate = tourneyDate,
            surface = surface,
            round = round,
            result = if (subjectWon) "W" else "L",
            opponentId = if (subjectWon) loserId else winnerId,
            opponentName = if (subjectWon) loserName else winnerName,
            score = score,
        )
    }
}
