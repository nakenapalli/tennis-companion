package com.tenniscompanion.integration

/**
 * Minimal slice of the upstream (SofaScore-derived) JSON — only the fields we use. Unknown fields
 * are ignored (Spring Boot disables fail-on-unknown-properties), so this stays small despite the
 * provider's large payloads. All nullable for resilience to missing data.
 */

data class LiveEventsResponse(val events: List<EventDto> = emptyList())

data class EventDto(
    val id: Long? = null,
    val homeTeam: TeamDto? = null,
    val awayTeam: TeamDto? = null,
    val homeScore: ScoreDto? = null,
    val awayScore: ScoreDto? = null,
    val status: StatusDto? = null,
    val tournament: TournamentDto? = null,
    val roundInfo: RoundDto? = null,
    val groundType: String? = null,
    val startTimestamp: Long? = null,
)

data class TeamDto(
    val id: Long? = null,
    val name: String? = null,
    val ranking: Int? = null,
    val gender: String? = null, // "M" | "F"
    val type: Int? = null, // 1 = single player, 2 = doubles pair
    val country: CountryDto? = null,
    val subTeams: List<Any?>? = null, // non-empty => doubles
)

data class CountryDto(val alpha3: String? = null)

data class ScoreDto(
    val current: Int? = null,
    val period1: Int? = null,
    val period2: Int? = null,
    val period3: Int? = null,
    val period4: Int? = null,
    val period5: Int? = null,
    val point: String? = null,
)

data class StatusDto(val type: String? = null, val description: String? = null)

data class TournamentDto(
    val id: Long? = null,
    val name: String? = null,
    val uniqueTournament: UniqueTournamentDto? = null,
    val category: CategoryDto? = null,
    val startTimestamp: Long? = null,
    val endTimestamp: Long? = null,
)

data class UniqueTournamentDto(val id: Long? = null, val name: String? = null, val groundType: String? = null)

data class CategoryDto(val name: String? = null, val slug: String? = null)

data class RoundDto(val name: String? = null)

data class RankingsResponse(val rankings: List<RankingEntryDto> = emptyList())

data class RankingEntryDto(
    val ranking: Int? = null,
    val points: Int? = null,
    val team: TeamDto? = null,
)
