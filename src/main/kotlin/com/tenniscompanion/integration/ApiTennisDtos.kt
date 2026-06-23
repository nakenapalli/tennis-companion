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
    // Match-detail extras — only populated when fetched by match_key (get_fixtures&match_key=…).
    @JsonProperty("pointbypoint") val pointByPoint: List<PbpGameDto>? = null,
    @JsonProperty("statistics") val statistics: List<StatisticDto>? = null,
)

/** One set's games for each side (api-tennis returns score as a per-set list). */
data class SetScoreDto(
    @JsonProperty("score_first") val scoreFirst: String? = null,
    @JsonProperty("score_second") val scoreSecond: String? = null,
    @JsonProperty("score_set") val scoreSet: String? = null,
)

/**
 * One game in the point-by-point feed. `set_number` is "Set 1"/"Set 2"; `player_served`/`serve_winner`/
 * `serve_lost` are "First Player"/"Second Player"; `score` is the game tally after this game ("1 - 0").
 * Note the `points` array OMITS the game-deciding point (the winner is `serve_winner`).
 */
data class PbpGameDto(
    @JsonProperty("set_number") val setNumber: String? = null,
    @JsonProperty("number_game") val numberGame: String? = null,
    @JsonProperty("player_served") val playerServed: String? = null,
    @JsonProperty("serve_winner") val serveWinner: String? = null,
    @JsonProperty("serve_lost") val serveLost: String? = null,
    @JsonProperty("score") val score: String? = null,
    @JsonProperty("points") val points: List<PbpPointDto>? = null,
)

/** One point within a game. `score` is the in-game score after the point ("30 - 15", first-second). The
 *  break/set/match_point fields name the player who HOLDS that point (e.g. "First Player"), else null. */
data class PbpPointDto(
    @JsonProperty("number_point") val numberPoint: String? = null,
    @JsonProperty("score") val score: String? = null,
    @JsonProperty("break_point") val breakPoint: String? = null,
    @JsonProperty("set_point") val setPoint: String? = null,
    @JsonProperty("match_point") val matchPoint: String? = null,
)

/**
 * One match-statistics row (undocumented but present in the live get_fixtures payload). One row per
 * player, per period ("match"/"set1"/…), per type ("Service"/"Return"/"Points"/"Games"). `statValue`
 * is a display string ("60%"); `statWon`/`statTotal` give the ratio when applicable.
 */
data class StatisticDto(
    @JsonProperty("player_key") val playerKey: String? = null,
    @JsonProperty("stat_period") val statPeriod: String? = null,
    @JsonProperty("stat_type") val statType: String? = null,
    @JsonProperty("stat_name") val statName: String? = null,
    @JsonProperty("stat_value") val statValue: String? = null,
    @JsonProperty("stat_won") val statWon: Int? = null,
    @JsonProperty("stat_total") val statTotal: Int? = null,
)

/**
 * One entry in the get_tournaments catalog (a static reference list of every tournament). Note the
 * vendor's misspelled field name `tournament_sourface` — the only place the feed exposes a surface.
 * `tournament_key` arrives as a JSON number; Jackson coerces it to String (same as on [FixtureDto]).
 */
data class TournamentCatalogDto(
    @JsonProperty("tournament_key") val tournamentKey: String? = null,
    @JsonProperty("tournament_name") val tournamentName: String? = null,
    @JsonProperty("event_type_type") val eventTypeType: String? = null,
    @JsonProperty("tournament_sourface") val surface: String? = null,
)

/** A player profile (get_players). `country` is a full English name; `birthday` is "dd.mm.yyyy". */
data class PlayerDto(
    @JsonProperty("player_key") val key: String? = null,
    @JsonProperty("player_name") val name: String? = null,
    @JsonProperty("player_country") val country: String? = null,
    @JsonProperty("player_bday") val birthday: String? = null,
    @JsonProperty("player_logo") val logo: String? = null,
    @JsonProperty("stats") val stats: List<PlayerStatDto>? = null,
)

/** A per-season line from a player's get_players `stats` array; numeric fields are stringified ints. */
data class PlayerStatDto(
    @JsonProperty("season") val season: String? = null,
    @JsonProperty("type") val type: String? = null, // "singles" | "doubles"
    @JsonProperty("rank") val rank: String? = null,
    @JsonProperty("titles") val titles: String? = null,
    @JsonProperty("matches_won") val matchesWon: String? = null,
    @JsonProperty("matches_lost") val matchesLost: String? = null,
    @JsonProperty("hard_won") val hardWon: String? = null,
    @JsonProperty("hard_lost") val hardLost: String? = null,
    @JsonProperty("clay_won") val clayWon: String? = null,
    @JsonProperty("clay_lost") val clayLost: String? = null,
    @JsonProperty("grass_won") val grassWon: String? = null,
    @JsonProperty("grass_lost") val grassLost: String? = null,
)

/** get_H2H result: prior meetings + each player's recent results. Entries reuse the fixture shape. */
data class H2HResultDto(
    @JsonProperty("H2H") val h2h: List<FixtureDto>? = null,
    @JsonProperty("firstPlayerResults") val firstPlayerResults: List<FixtureDto>? = null,
    @JsonProperty("secondPlayerResults") val secondPlayerResults: List<FixtureDto>? = null,
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
