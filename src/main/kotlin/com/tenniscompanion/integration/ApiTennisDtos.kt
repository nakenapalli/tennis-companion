package com.tenniscompanion.integration

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Minimal slice of the api-tennis.com JSON (snake_case → mapped with @JsonProperty). Only the fields
 * we use; unknown ones are ignored (fail-on-unknown-properties is off). All nullable for resilience.
 *
 * Note `jackson-annotations` is still the `com.fasterxml.jackson.annotation` package on Boot 4 even
 * though databind moved to `tools.jackson` — so @JsonProperty imports from there.
 */
data class ApiTennisResponse<T>(
    val success: Int? = null,
    val result: T? = null,
)

/** A fixture / live match (get_livescore + get_fixtures share this shape). */
data class FixtureDto(
    @JsonProperty("event_key") val eventKey: String? = null,
    @JsonProperty("event_date") val eventDate: String? = null,
    @JsonProperty("event_time") val eventTime: String? = null,
    @JsonProperty("event_first_player") val firstPlayer: String? = null,
    @JsonProperty("first_player_key") val firstPlayerKey: String? = null,
    @JsonProperty("event_second_player") val secondPlayer: String? = null,
    @JsonProperty("second_player_key") val secondPlayerKey: String? = null,
    @JsonProperty("event_final_result") val finalResult: String? = null,
    @JsonProperty("event_game_result") val gameResult: String? = null,
    @JsonProperty("event_serve") val serve: String? = null,
    @JsonProperty("event_winner") val winner: String? = null,
    @JsonProperty("event_status") val status: String? = null,
    @JsonProperty("event_type_type") val eventTypeType: String? = null,
    @JsonProperty("event_qualification") val qualification: String? = null, // "True"/"False" (sometimes blank) — the only qualifying-draw marker
    @JsonProperty("tournament_name") val tournamentName: String? = null,
    @JsonProperty("tournament_key") val tournamentKey: String? = null,
    @JsonProperty("tournament_round") val tournamentRound: String? = null,
    @JsonProperty("tournament_season") val tournamentSeason: String? = null,
    @JsonProperty("event_live") val live: String? = null,
    @JsonProperty("scores") val scores: List<SetScoreDto>? = null,
)

/** One set's games for each side (api-tennis returns score as a per-set list). */
data class SetScoreDto(
    @JsonProperty("score_first") val scoreFirst: String? = null,
    @JsonProperty("score_second") val scoreSecond: String? = null,
    @JsonProperty("score_set") val scoreSet: String? = null,
)

/** A standings/rankings row (get_standings). `country` is a full English name e.g. "Serbia". */
data class StandingDto(
    @JsonProperty("place") val place: String? = null,
    @JsonProperty("player") val player: String? = null,
    @JsonProperty("player_key") val playerKey: String? = null,
    @JsonProperty("league") val league: String? = null,
    @JsonProperty("movement") val movement: String? = null,
    @JsonProperty("country") val country: String? = null,
    @JsonProperty("points") val points: String? = null,
)
