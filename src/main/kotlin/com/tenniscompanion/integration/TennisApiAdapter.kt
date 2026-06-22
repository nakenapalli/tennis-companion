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

    /**
     * Recent matches a specific upstream player took part in (most recent first), keyed by the provider's
     * own player id. Used by the admin reconciliation review to disambiguate namesakes — seeing the
     * upstream player's actual recent results next to each candidate is the strongest "is this them?"
     * signal. Default: none.
     */
    fun fetchPlayerMatches(playerKey: String): List<NormalizedMatch> = emptyList()

    /**
     * The upstream player's profile (country / birth year / rank), keyed by the provider's player id —
     * the disambiguation signals the live-scores feed doesn't carry, fetched on demand for review.
     * Null if the provider exposes no profile. Default: none.
     */
    fun fetchPlayerProfile(playerKey: String): UpstreamPlayerProfile? = null

    /**
     * Full detail for one match (keyed by the provider's event id = our `external_id`): the reconstructed
     * point-by-point flow + per-period statistics, for the momentum and stats tabs. Null if the provider
     * exposes nothing for it (lower-circuit matches often carry no stats/point data). Default: none.
     */
    fun fetchMatchDetail(eventKey: String): NormalizedMatchDetail? = null

    /** Prior meetings between two upstream players (normalized so side 1 = `key1`). Default: none. */
    fun fetchH2H(key1: String, key2: String): List<NormalizedH2HMatch> = emptyList()

    /** A player's latest-season singles career line (titles, W-L, surface splits). Default: none. */
    fun fetchPlayerCareer(playerKey: String): NormalizedPlayerCareer? = null
}
