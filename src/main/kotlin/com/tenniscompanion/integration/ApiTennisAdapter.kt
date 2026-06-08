package com.tenniscompanion.integration

import com.tenniscompanion.config.TennisApiProperties
import com.tenniscompanion.reconcile.CountryCodes
import org.springframework.core.ParameterizedTypeReference
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * Concrete adapter for api-tennis.com. All provider specifics live here; callers only see the
 * normalized types. Auth is a query param (`APIkey`), the method is selected via `?method=`. Tour is
 * derived from the `event_type_type` / `league` strings; we keep singles only (MVP scope). The live
 * feed does not carry surface, so live surface is null. Country (standings only) is mapped to IOC for
 * reconciliation. `get_players` enrichment (birth year for ambiguous players) is a possible later
 * boost now that quota is generous — not wired in yet.
 */
@Component
class ApiTennisAdapter(
    private val client: RestClient,
    private val props: TennisApiProperties,
) : TennisApiAdapter {

    override val source = "api-tennis"

    override fun fetchLiveMatches(): List<NormalizedMatch> =
        fixtures("get_livescore").filter(::isSingles).mapNotNull(::toMatch)

    override fun fetchRankings(tour: String): List<NormalizedRanking> {
        val resp = client.get().uri { b ->
            b.queryParam("method", "get_standings").queryParam("APIkey", props.key)
                .queryParam("event_type", tour.uppercase()).build()
        }.retrieve().body(object : ParameterizedTypeReference<ApiTennisResponse<List<StandingDto>>>() {})
        return resp?.result.orEmpty().mapNotNull { toRanking(it, tour.uppercase()) }
    }

    /**
     * Current tournaments, deduped by NAME. api-tennis splits one tournament across many
     * tournament_keys — a Slam has separate keys for ATP/WTA singles, doubles, juniors, mixed — so we
     * collapse them into one entry, classify by the highest tier (a combined event → "ATP & WTA"), and
     * derive a real date range from its matches across a window (the API exposes no tournament-level
     * dates, only per-match event_date). Ended tournaments are dropped (kept if the last match is >= yesterday).
     */
    override fun fetchCurrentTournaments(): List<NormalizedTournament> {
        val today = LocalDate.now(ZoneOffset.UTC)
        return fixturesRange(today.minusDays(7).toString(), today.plusDays(14).toString())
            .filter { !it.tournamentName.isNullOrBlank() && it.tournamentKey != null }
            .groupBy { it.tournamentName!!.trim() }
            .map { (name, group) -> toTournament(name, group) }
            .filter { t -> t.endDate?.let { !it.isBefore(today.minusDays(1)) } ?: true }
    }

    /**
     * Recently completed singles, most-recent first. Spans yesterday+today (UTC) so the list isn't
     * empty around the UTC-midnight boundary. Capped — a "recently completed" glance, not an archive —
     * but the cap keeps EVERY main-tour result and trims only lower circuits (see [capRecent]). Shown
     * by the UI when nothing is live.
     */
    override fun fetchRecentMatches(): List<NormalizedMatch> {
        val today = LocalDate.now(ZoneOffset.UTC)
        val finished = fixturesRange(today.minusDays(1).toString(), today.toString())
            .filter(::isSingles)
            .mapNotNull(::toMatch)
            .filter { it.status == "finished" }
        return capRecent(finished)
    }

    private fun fixtures(method: String): List<FixtureDto> {
        val resp = client.get().uri { b ->
            b.queryParam("method", method).queryParam("APIkey", props.key)
                .queryParam("timezone", "UTC").build()
        }.retrieve().body(object : ParameterizedTypeReference<ApiTennisResponse<List<FixtureDto>>>() {})
        return resp?.result.orEmpty()
    }

    private fun fixturesRange(start: String, end: String): List<FixtureDto> {
        val resp = client.get().uri { b ->
            b.queryParam("method", "get_fixtures").queryParam("APIkey", props.key)
                .queryParam("date_start", start).queryParam("date_stop", end)
                .queryParam("timezone", "UTC").build()
        }.retrieve().body(object : ParameterizedTypeReference<ApiTennisResponse<List<FixtureDto>>>() {})
        return resp?.result.orEmpty()
    }

    // --- mapping (internal so it's unit-testable from hand-built DTOs, no HTTP) ---

    internal fun toMatch(f: FixtureDto): NormalizedMatch? {
        val id = f.eventKey ?: return null
        val tour = tourOf(f.eventTypeType)
        return NormalizedMatch(
            externalId = id,
            status = statusOf(f),
            tournamentExternalId = f.tournamentKey,
            tournamentName = f.tournamentName,
            round = f.tournamentRound?.ifBlank { null },
            surface = null, // not provided by this feed
            tour = tour,
            category = categoryOf(f.eventTypeType),
            player1 = NormalizedPlayerRef(externalId = f.firstPlayerKey ?: "", name = f.firstPlayer ?: "", tour = tour),
            player2 = NormalizedPlayerRef(externalId = f.secondPlayerKey ?: "", name = f.secondPlayer ?: "", tour = tour),
            score = scoreMap(f),
            startTime = startInstant(f),
            serve = if (statusOf(f) == "live") serveSide(f.serve) else null,
        )
    }

    /** event_serve is "First Player"/"Second Player"; normalize to the home/away side (null if absent). */
    internal fun serveSide(serve: String?): String? = when {
        serve.isNullOrBlank() -> null
        serve.contains("first", ignoreCase = true) -> "home"
        serve.contains("second", ignoreCase = true) -> "away"
        else -> null
    }

    internal fun toRanking(s: StandingDto, tour: String): NormalizedRanking? {
        val rank = s.place?.toIntOrNull() ?: return null
        val key = s.playerKey ?: return null
        val name = s.player ?: return null
        return NormalizedRanking(
            tour = tour,
            rank = rank,
            points = s.points?.toIntOrNull(),
            player = NormalizedPlayerRef(
                externalId = key,
                name = name,
                tour = (s.league ?: tour).uppercase(),
                countryCode = CountryCodes.toIoc(s.country),
                rankHint = rank,
            ),
        )
    }

    internal fun toTournament(name: String, group: List<FixtureDto>): NormalizedTournament {
        val cats = group.mapNotNull { categoryOf(it.eventTypeType) }.toSet()
        val level = when {
            "ATP" in cats && "WTA" in cats -> "ATP & WTA"
            "ATP" in cats -> "ATP"
            "WTA" in cats -> "WTA"
            "Challenger" in cats -> "Challenger"
            "ITF" in cats -> "ITF"
            "UTR" in cats -> "UTR"
            else -> cats.firstOrNull()
        }
        // Stable representative key: highest tier, then lowest key — so the entry's id doesn't churn.
        val rep = group.sortedWith(
            compareBy({ categoryRank(it.eventTypeType) }, { it.tournamentKey?.toLongOrNull() ?: Long.MAX_VALUE }),
        ).first()
        val dates = group.mapNotNull { it.eventDate?.let { d -> runCatching { LocalDate.parse(d) }.getOrNull() } }
        return NormalizedTournament(
            externalId = rep.tournamentKey!!,
            name = name,
            level = level,
            surface = null,
            tour = level?.takeIf { "ATP" in it || "WTA" in it },
            startDate = dates.minOrNull(),
            endDate = dates.maxOrNull(),
        )
    }

    internal fun isSingles(f: FixtureDto): Boolean = f.eventTypeType?.contains("Singles", ignoreCase = true) ?: false

    internal fun tourOf(eventType: String?): String =
        if (eventType != null && (eventType.contains("WTA", true) || eventType.contains("Women", true))) "WTA" else "ATP"

    internal fun statusOf(f: FixtureDto): String = when {
        f.live == "1" -> "live"
        !f.winner.isNullOrBlank() -> "finished"
        f.status.equals("Finished", ignoreCase = true) -> "finished"
        else -> "scheduled"
    }

    internal fun scoreMap(f: FixtureDto): Map<String, Any?> {
        val sets = f.scores.orEmpty().sortedBy { it.scoreSet?.toIntOrNull() ?: 0 }
        val homeSets = sets.mapNotNull { games(it.scoreFirst) }
        val awaySets = sets.mapNotNull { games(it.scoreSecond) }
        val (homePoint, awayPoint) = parseGameResult(f.gameResult)
        return mapOf(
            "home" to mapOf("sets" to homeSets, "point" to homePoint, "games" to homeSets.lastOrNull()),
            "away" to mapOf("sets" to awaySets, "point" to awayPoint, "games" to awaySets.lastOrNull()),
        )
    }

    /**
     * api-tennis encodes a tiebreak set as "games.tiebreakPoints" (e.g. "7.7" = 7 games / 7 TB points,
     * "6.5" = 6 games / 5 TB points). We display games only, so take the integer part before the dot —
     * `toIntOrNull()` alone would drop the whole set on any tiebreak. Plain "6" passes through unchanged.
     */
    internal fun games(raw: String?): Int? = raw?.substringBefore(".")?.trim()?.toIntOrNull()

    private fun parseGameResult(gr: String?): Pair<String?, String?> {
        val parts = gr?.split("-")?.map { it.trim() }?.takeIf { it.size == 2 } ?: return null to null
        fun clean(s: String) = s.takeIf { it.isNotBlank() && it != "-" }
        return clean(parts[0]) to clean(parts[1])
    }

    private fun startInstant(f: FixtureDto): Instant? {
        val d = f.eventDate ?: return null
        val t = f.eventTime?.takeIf { it.isNotBlank() } ?: "00:00"
        return runCatching { LocalDate.parse(d).atTime(LocalTime.parse(t)).toInstant(ZoneOffset.UTC) }.getOrNull()
    }

    /** Circuit/tier from the upstream event_type_type. Main tour ("Atp/Wta Singles", incl. Slams) → ATP/WTA. */
    internal fun categoryOf(eventType: String?): String? = when {
        eventType == null -> null
        eventType.contains("Grand Slam", true) -> "Grand Slam"
        eventType.contains("Challenger", true) -> "Challenger"
        eventType.contains("ITF", true) -> "ITF"
        eventType.contains("WTA", true) -> "WTA"
        eventType.contains("ATP", true) -> "ATP"
        eventType.contains("UTR", true) -> "UTR"
        eventType.contains("Boys", true) || eventType.contains("Girls", true) -> "Junior"
        else -> eventType
    }

    /** Tier priority for picking a tournament's representative fixture: main tour < challenger < itf < other. */
    private fun categoryRank(eventType: String?): Int = when (categoryOf(eventType)) {
        "ATP", "WTA", "Grand Slam" -> 0
        "Challenger" -> 1
        "ITF" -> 2
        else -> 3
    }

    /** ATP, WTA, and Grand Slam are "main tour"; Challenger/ITF/junior are lower circuits. */
    internal fun isMainTourCategory(category: String?): Boolean =
        category == "ATP" || category == "WTA" || category == "Grand Slam"

    /**
     * Trims the recent feed without letting lower circuits crowd out main-tour results. ITF/Challenger
     * can number in the hundreds a day, so a single global cap (by start time) would push an earlier-
     * started ATP/WTA/Slam match off the list — that's how a WTA semifinal went missing. So keep EVERY
     * main-tour result and cap only the lower circuits; re-sort the combined list newest-start-first.
     */
    internal fun capRecent(finished: List<NormalizedMatch>): List<NormalizedMatch> {
        val (mainTour, lower) = finished.partition { isMainTourCategory(it.category) }
        return (mainTour + lower.take(RECENT_LOWER_CAP)).sortedByDescending { it.startTime }
    }

    private companion object {
        const val RECENT_LOWER_CAP = 40 // lower-circuit results kept per refresh; main tour is uncapped
    }
}
