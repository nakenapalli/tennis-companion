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
        return resultOf("get_standings", resp).orEmpty().mapNotNull { toRanking(it, tour.uppercase()) }
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

    override fun fetchTournamentCatalog(): List<NormalizedTournamentCatalogEntry> {
        val resp = client.get().uri { b ->
            b.queryParam("method", "get_tournaments").queryParam("APIkey", props.key).build()
        }.retrieve().body(object : ParameterizedTypeReference<ApiTennisResponse<List<TournamentCatalogDto>>>() {})
        return resultOf("get_tournaments", resp).orEmpty().mapNotNull(::toCatalogEntry)
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

    /**
     * One upstream player's recent singles results, keyed by their api-tennis player id (the same
     * `event_first_player`/`second_player` key the reconciliation engine sees). `get_fixtures` accepts a
     * `player_key` filter, so this reuses the fixtures shape + [toMatch] mapping over a recent window.
     * Finished singles only, most-recent first — that's what helps a reviewer recognize the player.
     */
    override fun fetchPlayerMatches(playerKey: String): List<NormalizedMatch> {
        if (playerKey.isBlank()) return emptyList()
        val today = LocalDate.now(ZoneOffset.UTC)
        val resp = client.get().uri { b ->
            b.queryParam("method", "get_fixtures").queryParam("APIkey", props.key)
                .queryParam("player_key", playerKey)
                .queryParam("date_start", today.minusDays(PLAYER_MATCH_WINDOW_DAYS).toString())
                .queryParam("date_stop", today.toString())
                .queryParam("timezone", "UTC").build()
        }.retrieve().body(object : ParameterizedTypeReference<ApiTennisResponse<List<FixtureDto>>>() {})
        return resultOf("get_fixtures", resp).orEmpty()
            .filter(::isSingles)
            .mapNotNull(::toMatch)
            .filter { it.status == "finished" }
            .sortedByDescending { it.startTime }
    }

    /**
     * One upstream player's profile via `get_players&player_key=…` — country, birth year, and current
     * singles rank — for the review UI to disambiguate namesakes. The live-scores feed carries none of
     * this, so it's fetched on demand by key. Country is mapped to IOC (like standings); birth year is
     * pulled from the "dd.mm.yyyy" `player_bday`; rank is the latest-season singles `stats` row.
     */
    override fun fetchPlayerProfile(playerKey: String): UpstreamPlayerProfile? {
        if (playerKey.isBlank()) return null
        val resp = client.get().uri { b ->
            b.queryParam("method", "get_players").queryParam("APIkey", props.key)
                .queryParam("player_key", playerKey).build()
        }.retrieve().body(object : ParameterizedTypeReference<ApiTennisResponse<List<PlayerDto>>>() {})
        val p = resultOf("get_players", resp).orEmpty().firstOrNull() ?: return null
        return UpstreamPlayerProfile(
            country = CountryCodes.toIoc(p.country),
            birthYear = p.birthday?.let { YEAR.find(it)?.value?.toIntOrNull() },
            rank = p.stats.orEmpty()
                .filter { it.type?.contains("single", ignoreCase = true) == true }
                .maxByOrNull { it.season ?: "" }
                ?.rank?.toIntOrNull(),
        )
    }

    /**
     * Full detail for one match via `get_fixtures&match_key=…`. That single call returns the fixture
     * (with both player keys), the `statistics` array, and `pointbypoint` — so we resolve every stat row
     * and game to side 1 (first player) / side 2 (second player) here, and reconstruct each listed point's
     * winner. The player keys are kept so the H2H / career tabs can look players up on demand. Null only
     * when the fixture itself is missing (games/stats may be empty on lower circuits, but the keys are still
     * useful).
     */
    override fun fetchMatchDetail(eventKey: String): NormalizedMatchDetail? {
        if (eventKey.isBlank()) return null
        val resp = client.get().uri { b ->
            b.queryParam("method", "get_fixtures").queryParam("APIkey", props.key)
                .queryParam("match_key", eventKey).queryParam("timezone", "UTC").build()
        }.retrieve().body(object : ParameterizedTypeReference<ApiTennisResponse<List<FixtureDto>>>() {})
        val f = resultOf("get_fixtures", resp).orEmpty().firstOrNull() ?: return null

        val games = assembleGames(f.pointByPoint.orEmpty())
        val stats = f.statistics.orEmpty().mapNotNull { s -> toStat(s, f.firstPlayerKey) }
        return NormalizedMatchDetail(games, stats, f.firstPlayerKey, f.secondPlayerKey)
    }

    /**
     * Prior meetings between two players via `get_H2H`. The entries are fixtures (with per-set `scores`),
     * each carrying its own first/second player — so we resolve the winner and the scoreline relative to
     * `key1` (side 1). Meetings without a decided winner (e.g. in-progress) are dropped.
     */
    override fun fetchH2H(key1: String, key2: String): List<NormalizedH2HMatch> {
        if (key1.isBlank() || key2.isBlank()) return emptyList()
        val resp = client.get().uri { b ->
            b.queryParam("method", "get_H2H").queryParam("APIkey", props.key)
                .queryParam("first_player_key", key1).queryParam("second_player_key", key2).build()
        }.retrieve().body(object : ParameterizedTypeReference<ApiTennisResponse<H2HResultDto>>() {})
        return resultOf("get_H2H", resp)?.h2h.orEmpty().mapNotNull { toH2HMatch(it, key1) }
    }

    internal fun toH2HMatch(f: FixtureDto, key1: String): NormalizedH2HMatch? {
        val winnerKey = when {
            f.winner.equals("First Player", ignoreCase = true) -> f.firstPlayerKey
            f.winner.equals("Second Player", ignoreCase = true) -> f.secondPlayerKey
            else -> null
        } ?: return null
        val key1IsFirst = f.firstPlayerKey == key1
        val score = f.scores.orEmpty()
            .sortedBy { it.scoreSet?.toIntOrNull() ?: 0 }
            .mapNotNull { s ->
                val a = games(s.scoreFirst)
                val b = games(s.scoreSecond)
                if (a == null || b == null) null else if (key1IsFirst) "$a-$b" else "$b-$a"
            }
            .joinToString(", ").ifBlank { null }
        return NormalizedH2HMatch(
            date = f.eventDate,
            tournament = f.tournamentName,
            round = f.tournamentRound?.substringAfterLast(" - ")?.trim()?.ifBlank { null },
            winnerSide = if (winnerKey == key1) 1 else 2,
            score = score,
        )
    }

    /** A player's latest-season singles career line via `get_players` (titles, W-L, surface splits). */
    override fun fetchPlayerCareer(playerKey: String): NormalizedPlayerCareer? {
        if (playerKey.isBlank()) return null
        val resp = client.get().uri { b ->
            b.queryParam("method", "get_players").queryParam("APIkey", props.key)
                .queryParam("player_key", playerKey).build()
        }.retrieve().body(object : ParameterizedTypeReference<ApiTennisResponse<List<PlayerDto>>>() {})
        val p = resultOf("get_players", resp).orEmpty().firstOrNull() ?: return null
        val latest = p.stats.orEmpty()
            .filter { it.type?.contains("single", ignoreCase = true) == true }
            .maxByOrNull { it.season ?: "" }
        return NormalizedPlayerCareer(
            country = CountryCodes.toIoc(p.country),
            birthYear = p.birthday?.let { YEAR.find(it)?.value?.toIntOrNull() },
            rank = latest?.rank?.toIntOrNull(),
            logo = p.logo,
            season = latest?.season,
            titles = latest?.titles?.toIntOrNull(),
            wins = latest?.matchesWon?.toIntOrNull(),
            losses = latest?.matchesLost?.toIntOrNull(),
            hardWins = latest?.hardWon?.toIntOrNull(),
            hardLosses = latest?.hardLost?.toIntOrNull(),
            clayWins = latest?.clayWon?.toIntOrNull(),
            clayLosses = latest?.clayLost?.toIntOrNull(),
            grassWins = latest?.grassWon?.toIntOrNull(),
            grassLosses = latest?.grassLost?.toIntOrNull(),
        )
    }

    /** "First Player" → 1, "Second Player" → 2 (used for serve/winner and stat player keys). */
    private fun sideOfLabel(label: String?): Int? = when {
        label == null -> null
        label.contains("First", ignoreCase = true) -> 1
        label.contains("Second", ignoreCase = true) -> 2
        else -> null
    }

    internal fun toStat(s: StatisticDto, firstPlayerKey: String?): NormalizedStat? {
        val side = if (s.playerKey != null && s.playerKey == firstPlayerKey) 1 else 2
        val name = s.statName ?: return null
        return NormalizedStat(
            side = side,
            period = s.statPeriod ?: "match",
            type = s.statType ?: "Other",
            name = name,
            value = s.statValue,
            won = s.statWon,
            total = s.statTotal,
        )
    }

    internal fun toGame(g: PbpGameDto): NormalizedGame? {
        // The feed represents a tiebreak TWICE: once as the deciding game of its set (e.g. "Set 2"
        // game 13 = "7-6") AND again as a separate "Set N TieBreak" point list. The latter must be
        // dropped — its label has no set number, so the parse below falls back to set 1, and the
        // tiebreak's points get miscounted as a whole extra set (a phantom "7-5" in the scoreboard).
        // The set's own final game already records the outcome, so the detail list is redundant here.
        if (isTiebreakDetail(g.setNumber)) return null
        val server = sideOfLabel(g.playerServed) ?: return null
        val winner = sideOfLabel(g.serveWinner) ?: return null
        val setNo = g.setNumber?.substringAfterLast(' ')?.toIntOrNull() ?: 1
        val gameNo = g.numberGame?.toIntOrNull() ?: 0
        var prev = 0 to 0
        val points = g.points.orEmpty().map { p ->
            val cur = parsePointScore(p.score)
            val w = if (cur == null) null else pointWinner(prev, cur)
            if (cur != null) prev = cur
            NormalizedGamePoint(
                winnerSide = w,
                label = p.score?.replace(" ", "") ?: "",
                breakPoint = !p.breakPoint.isNullOrBlank(),
                setPoint = !p.setPoint.isNullOrBlank(),
                matchPoint = !p.matchPoint.isNullOrBlank(),
            )
        }
        return NormalizedGame(setNo, gameNo, server, winner, points)
    }

    /** A "Set N TieBreak" point-detail row (vs a real "Set N" game). Space/hyphen-insensitive. */
    internal fun isTiebreakDetail(setNumber: String?): Boolean =
        setNumber?.replace(" ", "")?.replace("-", "")?.contains("tiebreak", ignoreCase = true) == true

    /** Set number from a label like "Set 2" or "Set 2 TieBreak" (the suffix stripped first). */
    internal fun setNoOf(setNumber: String?): Int? =
        setNumber?.replace(Regex("(?i)tie[ -]?break"), "")?.trim()?.substringAfterLast(' ')?.toIntOrNull()

    /**
     * Builds the per-game point flow, folding each tiebreak's separate "Set N TieBreak" rows back into
     * that set's deciding game. The feed lists a tiebreak TWICE: once as the set's deciding game (e.g.
     * "Set 2" game 13 = "6-7") and again as one "Set N TieBreak" row PER POINT (winner in serve_winner,
     * running score in score). Those rows are often out of order and interleaved with the deciding game,
     * so we group + sort them by point number and attach them as the deciding game's points. Without
     * this the line jumps 6-6 → 6-7 in a single step (every tiebreak point skipped).
     */
    internal fun assembleGames(pbp: List<PbpGameDto>): List<NormalizedGame> {
        val regular = pbp.filterNot { isTiebreakDetail(it.setNumber) }.mapNotNull(::toGame)
        val tbPointsBySet = pbp.filter { isTiebreakDetail(it.setNumber) }
            .groupBy { setNoOf(it.setNumber) }
            .mapNotNull { (set, rows) -> set?.let { it to tiebreakPoints(rows) } }
            .toMap()
        if (tbPointsBySet.isEmpty()) return regular
        // a set's tiebreak is its highest-numbered game (game 13, played at 6-6); attach the points there
        val tiebreakGameIdx = regular.withIndex()
            .filter { tbPointsBySet.containsKey(it.value.setNumber) }
            .groupBy { it.value.setNumber }
            .mapValues { (_, rows) -> rows.maxBy { it.value.gameInSet }.index }
            .values.toSet()
        return regular.mapIndexed { i, g ->
            val pts = tbPointsBySet[g.setNumber]
            if (i in tiebreakGameIdx && !pts.isNullOrEmpty()) g.copy(points = pts, isTiebreak = true) else g
        }
    }

    /** Each "Set N TieBreak" row is one tiebreak point — ordered by point number, winner + server from the feed. */
    private fun tiebreakPoints(rows: List<PbpGameDto>): List<NormalizedGamePoint> =
        rows.sortedBy { it.numberGame?.toIntOrNull() ?: 0 }.mapNotNull { r ->
            val winner = sideOfLabel(r.serveWinner) ?: return@mapNotNull null
            NormalizedGamePoint(
                winnerSide = winner,
                label = r.score?.replace(" ", "") ?: "", // running tiebreak score, e.g. "5-3"
                breakPoint = false,
                setPoint = false,
                matchPoint = false,
                server = sideOfLabel(r.playerServed), // serve alternates in a tiebreak → enables mini-break detection
            )
        }

    /** Map a "30 - 15" in-game score to numeric ranks (0,15,30,40→0..3; A/AD→4). Null if unparseable. */
    private fun parsePointScore(score: String?): Pair<Int, Int>? {
        val parts = score?.split("-")?.map { it.trim() } ?: return null
        if (parts.size != 2) return null
        val a = POINT_RANK[parts[0]] ?: return null
        val b = POINT_RANK[parts[1]] ?: return null
        return a to b
    }

    /** Who won the point given the (prev → cur) in-game score, handling deuce regress (advantage lost). */
    private fun pointWinner(prev: Pair<Int, Int>, cur: Pair<Int, Int>): Int? {
        val da = cur.first - prev.first
        val db = cur.second - prev.second
        return when {
            da > 0 && db <= 0 -> 1
            db > 0 && da <= 0 -> 2
            prev.first == 4 && cur.first == 3 -> 2 // side 1 lost its advantage
            prev.second == 4 && cur.second == 3 -> 1
            else -> null
        }
    }

    private fun fixtures(method: String): List<FixtureDto> {
        val resp = client.get().uri { b ->
            b.queryParam("method", method).queryParam("APIkey", props.key)
                .queryParam("timezone", "UTC").build()
        }.retrieve().body(object : ParameterizedTypeReference<ApiTennisResponse<List<FixtureDto>>>() {})
        return resultOf(method, resp).orEmpty()
    }

    private fun fixturesRange(start: String, end: String): List<FixtureDto> {
        val resp = client.get().uri { b ->
            b.queryParam("method", "get_fixtures").queryParam("APIkey", props.key)
                .queryParam("date_start", start).queryParam("date_stop", end)
                .queryParam("timezone", "UTC").build()
        }.retrieve().body(object : ParameterizedTypeReference<ApiTennisResponse<List<FixtureDto>>>() {})
        return resultOf("get_fixtures", resp).orEmpty()
    }

    /**
     * Unwraps the api-tennis envelope, distinguishing a *failure* from a legitimately empty result. The
     * feed returns `success: 1` on success (the payload may still be an empty list — e.g. nothing live)
     * and `success: 0` with an `error` message on failure, often with an HTTP 200 — so we can't rely on
     * the status code alone. Anything that isn't a clean success throws [UpstreamApiException] so the
     * poll path skips the write and keeps the last-good snapshot, instead of treating an error as
     * "nothing is happening" and wiping live/recent data. A clean success with a null/empty result is
     * returned as-is (genuinely empty).
     */
    internal fun <T> resultOf(method: String, resp: ApiTennisResponse<T>?): T? {
        if (resp == null) throw UpstreamApiException("api-tennis '$method' returned no body")
        // `success` is sometimes absent on valid payloads; only an explicit non-1 value is a failure.
        if (resp.success != null && resp.success != 1) {
            throw UpstreamApiException("api-tennis '$method' failed (success=${resp.success})")
        }
        return resp.result
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
            round = f.tournamentRound?.ifBlank { null }?.let(::normalizeRound),
            surface = null, // not provided by this feed
            tour = tour,
            category = categoryOf(f.eventTypeType),
            qualifying = isQualifying(f.qualification),
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

    internal fun toCatalogEntry(d: TournamentCatalogDto): NormalizedTournamentCatalogEntry? {
        val key = d.tournamentKey?.ifBlank { null } ?: return null
        return NormalizedTournamentCatalogEntry(
            externalId = key,
            name = d.tournamentName?.trim().orEmpty(),
            surface = canonicalSurface(d.surface),
        )
    }

    /**
     * Maps the upstream's messy `tournament_sourface` vocabulary onto our canonical {Hard, Clay, Grass}.
     * Handles case ("hard"), the "(Indoor)" suffix (our model has no indoor flag, so it collapses to the
     * base surface), and rejects blanks / non-surface labels (team-competition stages like "- Promotion")
     * by returning null.
     */
    internal fun canonicalSurface(raw: String?): String? = when {
        raw == null -> null
        raw.trim().startsWith("Grass", ignoreCase = true) -> "Grass"
        raw.trim().startsWith("Clay", ignoreCase = true) -> "Clay"
        raw.trim().startsWith("Hard", ignoreCase = true) -> "Hard"
        else -> null
    }

    internal fun isSingles(f: FixtureDto): Boolean = f.eventTypeType?.contains("Singles", ignoreCase = true) ?: false

    /**
     * Whether a fixture is a qualifying-draw match. The feed reuses main-draw round names ("Final",
     * "Semi-finals") for the qualifying draw, so `event_qualification` ("True"/"False", sometimes blank)
     * is the only thing that distinguishes a qualifying final from the real tournament final.
     */
    internal fun isQualifying(flag: String?): Boolean = flag.equals("true", ignoreCase = true)

    /**
     * Standardizes the feed's fraction-style round names to the conventional form. api-tennis emits early
     * rounds as "1/N-finals" (a "Nth-finals" / French-style label); we rewrite the round token after the
     * last " - " (so the "WTA Berlin - " prefix is preserved) while leaving already-named rounds and any
     * unrecognized strings untouched. See [formalRoundName] for the mapping.
     */
    internal fun normalizeRound(round: String): String {
        val sep = " - "
        val idx = round.lastIndexOf(sep)
        val token = if (idx >= 0) round.substring(idx + sep.length) else round
        val formal = formalRoundName(token) ?: return round
        return if (idx >= 0) round.substring(0, idx + sep.length) + formal else formal
    }

    /**
     * "1/N-finals" → its conventional name, for a *valid* round only: numerator 1 and a power-of-two
     * denominator. The denominator is half the field, so 1/N-finals is the round of 2N — e.g. 1/16-finals
     * → "Round of 32", 1/8-finals → "Round of 16". The small ones keep their established names
     * (1/4 → Quarter-finals, 1/2 → Semi-finals, 1/1 → Final). Returns null for anything that isn't a valid
     * fraction round (already-named rounds, non-power-of-two denominators, junk) so the caller leaves it as-is.
     */
    internal fun formalRoundName(token: String): String? {
        val m = FRACTION_ROUND.matchEntire(token.trim()) ?: return null
        val denom = m.groupValues[1].toIntOrNull() ?: return null
        if (denom < 1 || (denom and (denom - 1)) != 0) return null // denominator must be a power of two
        return when (val players = denom * 2) {
            2 -> "Final"
            4 -> "Semi-finals"
            8 -> "Quarter-finals"
            else -> "Round of $players"
        }
    }

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
        const val PLAYER_MATCH_WINDOW_DAYS = 180L // lookback for a player's recent results in the review UI
        val YEAR = Regex("\\d{4}") // first 4-digit run in a "dd.mm.yyyy" birth date

        // In-game point scores → numeric rank; advantage ("A"/"AD") shares 4 with the deuce-regress logic.
        val POINT_RANK = mapOf("0" to 0, "15" to 1, "30" to 2, "40" to 3, "A" to 4, "AD" to 4)

        // "1/16-finals", "1/8 Finals", etc. — the denominator is captured; separators/case are lenient.
        val FRACTION_ROUND = Regex("^1/(\\d+)[\\s-]*finals?$", RegexOption.IGNORE_CASE)
    }
}
