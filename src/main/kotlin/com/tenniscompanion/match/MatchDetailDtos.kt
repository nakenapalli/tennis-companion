package com.tenniscompanion.match

/** Momentum-tab payload: the line series + break markers + set brackets + a few headline numbers. */
data class MomentumDto(
    val bestOf: Int,
    val player1: String,
    val player2: String,
    val series: List<MomentumPoint>,
    val breaks: List<MomentumBreak>,
    val sets: List<MomentumSet>,
    val meta: MomentumMeta,
)

/** Stats-tab payload: per-period (match / set1 / …) groups, each a list of comparison rows. */
data class MatchStatsDto(
    val player1: String,
    val player2: String,
    val periods: List<String>,
    val groups: Map<String, List<StatGroupDto>>, // period -> groups
)

data class StatGroupDto(val type: String, val rows: List<StatRowDto>)

data class StatRowDto(val name: String, val p1: StatCellDto, val p2: StatCellDto)

/** One player's value for a stat. `value` is the display string ("60%"); `won`/`total` give the ratio. */
data class StatCellDto(val value: String?, val won: Int?, val total: Int?)

/** Head-to-head tab: the record + prior meetings. `source` is "historical" (Sackmann) or "live" (feed). */
data class H2hViewDto(
    val player1: String,
    val player2: String,
    val p1Wins: Int,
    val p2Wins: Int,
    val source: String,
    val meetings: List<H2hMeetingDto>,
)

data class H2hMeetingDto(
    val date: String?,
    val tournament: String?,
    val round: String?,
    val surface: String?,
    val winner: Int, // 1 | 2
    val score: String?,
)

/** Players tab: side-by-side bios (DB profile + live career splits). */
data class PlayersViewDto(val player1: PlayerBioDto, val player2: PlayerBioDto)

data class PlayerBioDto(
    val name: String,
    val country: String?,
    val hand: String?,
    val heightCm: Int?,
    val age: Int?,
    val rank: Int?,
    val logo: String?,
    val season: String?, // latest singles season the career splits cover
    val titles: Int?,
    val wins: Int?,
    val losses: Int?,
    val hardWins: Int?,
    val hardLosses: Int?,
    val clayWins: Int?,
    val clayLosses: Int?,
    val grassWins: Int?,
    val grassLosses: Int?,
)
