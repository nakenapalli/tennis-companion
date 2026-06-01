package com.tenniscompanion.api

import com.tenniscompanion.domain.Match
import com.tenniscompanion.domain.MatchRepository
import com.tenniscompanion.domain.PlayerRepository
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.time.LocalDate

@Service
class PlayerService(
    private val players: PlayerRepository,
    private val matches: MatchRepository,
    private val jdbc: JdbcTemplate,
) {

    fun profile(playerId: Long): PlayerProfileDto? {
        // `?.let { ... }` returns null cleanly when the player isn't found — Kotlin null-safety.
        val p = players.findById(playerId).orElse(null) ?: return null
        val (rank, rankDate) = currentRank(p.playerId, p.tour)
        return PlayerProfileDto(
            playerId = p.playerId,
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

    fun recentMatches(playerId: Long, limit: Int): List<MatchDto> {
        val page = PageRequest.of(0, limit.coerceIn(1, 100), Sort.by(Sort.Direction.DESC, "tourneyDate"))
        return matches.findByWinnerIdOrLoserId(playerId, playerId, page).map { it.toDto(playerId) }
    }

    fun headToHead(playerId: Long, opponentId: Long): H2hDto {
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

    /** Latest ranking row for this player+tour (a small JDBC query — no entity for rankings_history). */
    private fun currentRank(playerId: Long, tour: String): Pair<Int?, LocalDate?> {
        val sql = """
            SELECT rank, ranking_date FROM rankings_history
            WHERE player_id = ? AND tour = ?
            ORDER BY ranking_date DESC
            LIMIT 1
        """
        return jdbc.query(sql, { rs, _ ->
            (rs.getObject("rank") as? Int) to rs.getDate("ranking_date")?.toLocalDate()
        }, playerId, tour).firstOrNull() ?: (null to null)
    }

    /** Project a match onto the subject player's perspective. An extension function keeps it tidy. */
    private fun Match.toDto(subjectId: Long): MatchDto {
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
