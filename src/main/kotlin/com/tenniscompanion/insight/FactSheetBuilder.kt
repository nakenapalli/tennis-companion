package com.tenniscompanion.insight

import com.tenniscompanion.api.PlayerService
import com.tenniscompanion.integration.TennisApiAdapter
import com.tenniscompanion.poller.LiveDataStore
import com.tenniscompanion.poller.TournamentStore
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.UUID

/** The assembled fact sheet plus the set of entity names it contains (for output validation). */
data class FactSheet(val data: Map<String, Any?>, val entityNames: Set<String>) {
    @Suppress("UNCHECKED_CAST")
    val isEmpty: Boolean
        get() = (data["tournaments"] as List<Any?>).isEmpty() && (data["notable_matchups"] as List<Any?>).isEmpty()
}

/**
 * Assembles a tight, fully-grounded fact sheet from the database — every value is real. The LLM only
 * writes narrative around these facts (design §9). Player names + ranks come from the reconciled
 * Sackmann profile (cleaner than the feed's "C. Alcaraz" display strings), H2H + form from `matches`.
 * Kept small on purpose: a handful of tournaments, matchups, and form lines beat an exhaustive dump.
 */
@Component
class FactSheetBuilder(
    private val liveData: LiveDataStore,
    private val tournaments: TournamentStore,
    private val players: PlayerService,
    private val adapter: TennisApiAdapter,
) {

    fun build(weekOf: LocalDate): FactSheet {
        val source = adapter.source
        val names = linkedSetOf<String>()

        // --- current main-tour tournaments ---
        val tournamentsOut = tournaments.current(source).filter { isMainTour(it.level) }.take(5).map { t ->
            names += t.name
            linkedMapOf(
                "name" to t.name, "level" to t.level, "surface" to t.surface,
                "location" to t.location, "starts" to t.startDate?.toString(), "ends" to t.endDate?.toString(),
            )
        }

        // --- top players per tour (rankings) ---
        fun topList(tour: String) = liveData.rankings(tour, 6).map {
            names += it.name
            linkedMapOf("name" to it.name, "rank" to it.rank, "country" to it.country)
        }
        val topPlayers = linkedMapOf("ATP" to topList("ATP"), "WTA" to topList("WTA"))

        // --- notable matchups: FINISHED main-tour matches where both players are reconciled ---
        // Results roundup only — narrating an in-progress match's "result" is what invited errors.
        val matches = liveData.recentMatches(source)
            .filter { it.status == "finished" && isMainTour(it.category) && it.player1.playerId != null && it.player2.playerId != null }
            .distinctBy { it.externalId }
            .take(6)

        // resolve each player once → canonical name + rank from Sackmann
        val ids = matches.flatMap { listOf(it.player1.playerId!!, it.player2.playerId!!) }.distinct()
        val profiles = ids.associateWith { players.profile(it) }
        fun nameOf(id: UUID, fallback: String): String =
            profiles[id]?.let { listOfNotNull(it.firstName, it.lastName).joinToString(" ").ifBlank { null } } ?: fallback
        fun rankOf(id: UUID): Int? = profiles[id]?.currentRank

        val matchups = matches.mapNotNull { m ->
            val side = MatchFacts.winnerOf(m.score) ?: return@mapNotNull null // skip if winner undeterminable
            val homeId = m.player1.playerId!!; val awayId = m.player2.playerId!!
            val winnerId = if (side == "home") homeId else awayId
            val loserId = if (side == "home") awayId else homeId
            val winnerName = nameOf(winnerId, if (side == "home") m.player1.name else m.player2.name)
            val loserName = nameOf(loserId, if (side == "home") m.player2.name else m.player1.name)
            names += winnerName; names += loserName

            // H2H from the winner's perspective (playerWins = winner's career wins over loser)
            val h2h = players.headToHead(winnerId, loserId)
            val h2hSummary = when {
                h2h.playerWins + h2h.opponentWins == 0 -> "First career meeting"
                h2h.playerWins > h2h.opponentWins -> "$winnerName leads ${h2h.playerWins}-${h2h.opponentWins}"
                h2h.playerWins < h2h.opponentWins -> "$loserName leads ${h2h.opponentWins}-${h2h.playerWins}"
                else -> "Tied ${h2h.playerWins}-${h2h.opponentWins}"
            }
            val last = h2h.matches.maxByOrNull { it.tourneyDate ?: LocalDate.MIN }
            val lastMeeting = last?.let {
                val w = if (it.result == "W") winnerName else (it.opponentName ?: loserName)
                val yr = it.tourneyDate?.year?.let { y -> "$y " } ?: ""
                "previous meeting: $yr${it.tourneyName ?: "tournament"}, $w won ${it.score ?: "?"}"
            }

            val matchup = linkedMapOf<String, Any?>(
                "result" to MatchFacts.resultLine(winnerName, loserName, m.score, side),
                "round" to MatchFacts.cleanRound(m.round),
                "tournament" to m.tournamentName,
                "winner" to linkedMapOf("name" to winnerName, "rank" to rankOf(winnerId)),
                "loser" to linkedMapOf("name" to loserName, "rank" to rankOf(loserId)),
                "h2h" to h2hSummary,
                "context" to lastMeeting,
            )
            if (matchup["round"] == null) matchup.remove("round") // omit unknown stage rather than guess
            matchup
        }

        // --- recent form for the matchup players ---
        val form = ids.take(8).mapNotNull { id ->
            val recent = players.recentMatches(id, 3)
            if (recent.isEmpty()) return@mapNotNull null
            val name = nameOf(id, "")
            if (name.isBlank()) return@mapNotNull null
            names += name
            linkedMapOf(
                "name" to name,
                "recent_results" to recent.map { r ->
                    r.opponentName?.let { names += it }
                    listOfNotNull(r.result, "vs", r.opponentName, r.score, r.round?.let { "($it)" })
                        .joinToString(" ")
                },
            )
        }

        val data = linkedMapOf<String, Any?>(
            "week_of" to weekOf.toString(),
            "tournaments" to tournamentsOut,
            "top_players" to topPlayers,
            "notable_matchups" to matchups,
            "player_form" to form,
        )
        return FactSheet(data, names)
    }

    private fun isMainTour(category: String?): Boolean =
        category != null && (category.contains("ATP") || category.contains("WTA"))
}
