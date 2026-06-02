package com.tenniscompanion.integration

import org.springframework.web.client.RestClient
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Concrete adapter for RapidAPI "TennisApi" (SofaScore-derived). All provider specifics live here;
 * callers only see the normalized types. Filters to singles (the MVP scope) and derives tour from
 * the player's gender (M->ATP, F->WTA).
 *
 * RETIRED 2026-06-01: replaced by [ApiTennisAdapter] (api-tennis.com). Kept un-annotated (not a bean)
 * for reference; delete once the new provider is proven. Needs the old RapidAPI `RestClientConfig`
 * headers + `SofaScoreDtos` if ever reactivated.
 */
class RapidApiTennisAdapter(private val client: RestClient) : TennisApiAdapter {

    override val source = "tennisapi"

    override fun fetchLiveMatches(): List<NormalizedMatch> {
        val resp = client.get().uri("/api/tennis/events/live").retrieve().body(LiveEventsResponse::class.java)
        return resp?.events.orEmpty().filter(::isSingles).mapNotNull(::toMatch)
    }

    override fun fetchRankings(tour: String): List<NormalizedRanking> {
        val resp = client.get().uri("/api/tennis/rankings/{tour}", tour.lowercase())
            .retrieve().body(RankingsResponse::class.java)
        return resp?.rankings.orEmpty().mapNotNull { entry ->
            val team = entry.team ?: return@mapNotNull null
            val rank = entry.ranking ?: return@mapNotNull null
            NormalizedRanking(
                tour = tour.uppercase(),
                rank = rank,
                points = entry.points,
                player = playerRef(team, tour.uppercase()),
            )
        }
    }

    /** No "list tournaments" endpoint on this tier — derive the current set from live events. */
    override fun fetchCurrentTournaments(): List<NormalizedTournament> {
        val resp = client.get().uri("/api/tennis/events/live").retrieve().body(LiveEventsResponse::class.java)
        return resp?.events.orEmpty()
            .mapNotNull { it.tournament }
            .distinctBy { it.uniqueTournament?.id ?: it.id }
            .mapNotNull { t ->
                val id = (t.uniqueTournament?.id ?: t.id)?.toString() ?: return@mapNotNull null
                NormalizedTournament(
                    externalId = id,
                    name = t.uniqueTournament?.name ?: t.name ?: "Unknown",
                    level = t.category?.name,
                    surface = normalizeSurface(t.uniqueTournament?.groundType),
                    startDate = tsToDate(t.startTimestamp),
                    endDate = tsToDate(t.endTimestamp),
                )
            }
    }

    // --- mapping helpers ---

    private fun isSingles(e: EventDto): Boolean =
        e.homeTeam?.subTeams.isNullOrEmpty() && e.awayTeam?.subTeams.isNullOrEmpty()

    private fun toMatch(e: EventDto): NormalizedMatch? {
        val id = e.id ?: return null
        val home = e.homeTeam ?: return null
        val away = e.awayTeam ?: return null
        val tour = tourOf(home)
        return NormalizedMatch(
            externalId = id.toString(),
            status = statusOf(e.status?.type),
            tournamentExternalId = (e.tournament?.uniqueTournament?.id ?: e.tournament?.id)?.toString(),
            tournamentName = e.tournament?.uniqueTournament?.name ?: e.tournament?.name,
            round = e.roundInfo?.name,
            surface = normalizeSurface(e.groundType),
            tour = tour,
            player1 = playerRef(home, tour),
            player2 = playerRef(away, tour),
            score = scoreMap(e),
            startTime = e.startTimestamp?.let { Instant.ofEpochSecond(it) },
        )
    }

    private fun playerRef(team: TeamDto, tour: String) = NormalizedPlayerRef(
        externalId = team.id?.toString() ?: "",
        name = team.name ?: "",
        tour = tour,
        countryCode = team.country?.alpha3,
        rankHint = team.ranking,
    )

    private fun tourOf(team: TeamDto): String = if (team.gender.equals("F", ignoreCase = true)) "WTA" else "ATP"

    private fun statusOf(type: String?): String = when (type) {
        "inprogress" -> "live"
        "finished" -> "finished"
        else -> "scheduled"
    }

    private fun tsToDate(ts: Long?): LocalDate? =
        ts?.let { Instant.ofEpochSecond(it).atOffset(ZoneOffset.UTC).toLocalDate() }

    private fun normalizeSurface(ground: String?): String? = when {
        ground == null -> null
        ground.contains("clay", true) -> "Clay"
        ground.contains("grass", true) -> "Grass"
        ground.contains("hard", true) -> "Hard"
        else -> ground
    }

    private fun scoreMap(e: EventDto): Map<String, Any?> = mapOf(
        "home" to sideScore(e.homeScore),
        "away" to sideScore(e.awayScore),
    )

    private fun sideScore(s: ScoreDto?): Map<String, Any?> = mapOf(
        "sets" to listOfNotNull(s?.period1, s?.period2, s?.period3, s?.period4, s?.period5),
        "point" to s?.point,
        "games" to s?.current,
    )
}
