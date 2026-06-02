package com.tenniscompanion.integration

/**
 * The single seam to the upstream live feed. All provider-specific knowledge (auth, endpoints,
 * rate limits, response DTOs) lives behind this interface; everything else depends only on the
 * normalized return types. A concrete implementation is added in the live-polling slice once a
 * provider + API key is chosen (design §6.1). `source` identifies the provider in `entity_map`.
 */
interface TennisApiAdapter {
    val source: String

    fun fetchLiveMatches(): List<NormalizedMatch>

    fun fetchRankings(tour: String): List<NormalizedRanking>

    fun fetchCurrentTournaments(): List<NormalizedTournament>

    /** Today's completed matches, for the "completed today" view when nothing is live. Default: none. */
    fun fetchRecentMatches(): List<NormalizedMatch> = emptyList()
}
