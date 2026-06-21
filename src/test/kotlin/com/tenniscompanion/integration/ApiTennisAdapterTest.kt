package com.tenniscompanion.integration

import com.tenniscompanion.config.TennisApiProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient
import java.time.Instant
import java.time.LocalDate

/**
 * Pure mapping tests over the (internal) DTO→Normalized helpers — no HTTP. JSON binding of the
 * snake_case fields is exercised live against the real api-tennis feed.
 */
class ApiTennisAdapterTest {

    private val adapter = ApiTennisAdapter(RestClient.create(), TennisApiProperties())

    @Test
    fun `derives tour from event_type_type`() {
        assertEquals("ATP", adapter.tourOf("Atp Singles"))
        assertEquals("ATP", adapter.tourOf("Challenger Men Singles"))
        assertEquals("WTA", adapter.tourOf("Itf Women Singles"))
        assertEquals("WTA", adapter.tourOf("WTA Singles"))
    }

    @Test
    fun `categoryOf classifies the circuit from event_type_type`() {
        assertEquals("ATP", adapter.categoryOf("Atp Singles"))
        assertEquals("WTA", adapter.categoryOf("Wta Singles"))
        assertEquals("Challenger", adapter.categoryOf("Challenger Men Singles"))
        assertEquals("ITF", adapter.categoryOf("Itf Women Singles"))
        assertEquals("Junior", adapter.categoryOf("Boys Singles"))
        assertNull(adapter.categoryOf(null))
    }

    @Test
    fun `resultOf unwraps a successful envelope and tolerates a missing success flag`() {
        assertEquals(listOf("a"), adapter.resultOf("m", ApiTennisResponse(success = 1, result = listOf("a"))))
        // `success` is sometimes absent on valid payloads — that must not be treated as a failure.
        assertEquals(listOf("a"), adapter.resultOf("m", ApiTennisResponse(success = null, result = listOf("a"))))
    }

    @Test
    fun `resultOf throws on an error envelope or missing body so the poll path keeps last-good data`() {
        assertThrows(UpstreamApiException::class.java) {
            adapter.resultOf<List<String>>("m", ApiTennisResponse(success = 0, result = null))
        }
        assertThrows(UpstreamApiException::class.java) {
            adapter.resultOf<List<String>>("m", null)
        }
    }

    @Test
    fun `keeps singles, drops doubles`() {
        assertTrue(adapter.isSingles(FixtureDto(eventTypeType = "Atp Singles")))
        assertFalse(adapter.isSingles(FixtureDto(eventTypeType = "Atp Doubles")))
        assertFalse(adapter.isSingles(FixtureDto(eventTypeType = null)))
    }

    @Test
    fun `status from live, winner, finished, else scheduled`() {
        assertEquals("live", adapter.statusOf(FixtureDto(live = "1")))
        assertEquals("finished", adapter.statusOf(FixtureDto(live = "0", winner = "First Player")))
        assertEquals("finished", adapter.statusOf(FixtureDto(live = "0", status = "Finished")))
        assertEquals("scheduled", adapter.statusOf(FixtureDto(live = "0")))
    }

    @Test
    fun `maps a live fixture to a normalized match with per-set scores`() {
        val f = FixtureDto(
            eventKey = "143192", eventTypeType = "Atp Singles", live = "1",
            firstPlayer = "S. Bejlek", firstPlayerKey = "9393",
            secondPlayer = "R. Zarazua", secondPlayerKey = "1805",
            gameResult = "30 - 15", tournamentName = "ITF W60", tournamentRound = "",
            scores = listOf(SetScoreDto("6", "4", "1"), SetScoreDto("5", "5", "2")),
        )
        val m = adapter.toMatch(f)!!
        assertEquals("143192", m.externalId)
        assertEquals("live", m.status)
        assertEquals("ATP", m.tour)
        assertNull(m.round) // blank round normalizes to null
        assertNull(m.surface) // feed doesn't provide surface
        assertEquals("ATP", m.category) // "Atp Singles" -> main tour
        assertEquals("9393", m.player1.externalId)
        assertEquals("R. Zarazua", m.player2.name)

        @Suppress("UNCHECKED_CAST")
        val home = m.score!!["home"] as Map<String, Any?>
        assertEquals(listOf(6, 5), home["sets"])
        assertEquals("30", home["point"])
    }

    @Test
    fun `qualifying flag comes from event_qualification`() {
        assertTrue(adapter.isQualifying("True"))
        assertTrue(adapter.isQualifying("true"))
        assertFalse(adapter.isQualifying("False"))
        assertFalse(adapter.isQualifying(""))
        assertFalse(adapter.isQualifying(null))
        // the qualifying final reuses the main-draw round name — only the flag distinguishes it
        val qualFinal = FixtureDto(
            eventKey = "12136561", eventTypeType = "Wta Singles", live = "0", winner = "Second Player",
            tournamentName = "Berlin", tournamentRound = "WTA Berlin - Final", qualification = "True",
        )
        assertTrue(adapter.toMatch(qualFinal)!!.qualifying)
        assertFalse(adapter.toMatch(qualFinal.copy(qualification = "False"))!!.qualifying)
    }

    @Test
    fun `normalizeRound rewrites fraction rounds, keeps the prefix, leaves other rounds alone`() {
        // 1/N-finals -> round of 2N, preserving the "Event - " prefix
        assertEquals("WTA Berlin - Round of 32", adapter.normalizeRound("WTA Berlin - 1/16-finals"))
        assertEquals("WTA Berlin - Round of 64", adapter.normalizeRound("WTA Berlin - 1/32-finals"))
        assertEquals("ATP Halle - Round of 16", adapter.normalizeRound("ATP Halle - 1/8-finals"))
        assertEquals("Foo - Round of 128", adapter.normalizeRound("Foo - 1/64-finals"))
        // small denominators keep their established names
        assertEquals("Foo - Quarter-finals", adapter.normalizeRound("Foo - 1/4-finals"))
        assertEquals("Foo - Semi-finals", adapter.normalizeRound("Foo - 1/2-finals"))
        // already-named and non-power-of-two / junk rounds are untouched
        assertEquals("WTA Berlin - Final", adapter.normalizeRound("WTA Berlin - Final"))
        assertEquals("WTA Berlin - Semi-finals", adapter.normalizeRound("WTA Berlin - Semi-finals"))
        assertEquals("Foo - 1/3-finals", adapter.normalizeRound("Foo - 1/3-finals")) // 3 is not a power of two
        assertEquals("Foo - 1/6-finals", adapter.normalizeRound("Foo - 1/6-finals"))
        // no " - " prefix: convert the whole token
        assertEquals("Round of 32", adapter.normalizeRound("1/16-finals"))
    }

    @Test
    fun `toMatch standardizes a fraction round name`() {
        val f = FixtureDto(
            eventKey = "12136557", eventTypeType = "Wta Singles", live = "0", winner = "First Player",
            tournamentName = "Berlin", tournamentRound = "WTA Berlin - 1/16-finals",
        )
        assertEquals("WTA Berlin - Round of 32", adapter.toMatch(f)!!.round)
    }

    @Test
    fun `games() takes the integer part so tiebreak sets are not dropped`() {
        // api-tennis encodes a tiebreak set as "games.tiebreakPoints": 7-6(5) -> "7.7"/"6.5".
        assertEquals(7, adapter.games("7.7"))
        assertEquals(6, adapter.games("6.5"))
        assertEquals(6, adapter.games("6")) // plain set unchanged
        assertNull(adapter.games("-"))
        assertNull(adapter.games(null))
    }

    @Test
    fun `a finished match with a tiebreak set keeps every set`() {
        val f = FixtureDto(
            eventKey = "999", eventTypeType = "Atp Singles", live = "0", winner = "First Player",
            firstPlayer = "F. Cobolli", secondPlayer = "Z. Svajda", gameResult = "-",
            scores = listOf(
                SetScoreDto("6", "2", "1"),
                SetScoreDto("6", "3", "2"),
                SetScoreDto("7.7", "6.5", "3"), // 7-6(5) tiebreak set — must not be dropped
            ),
        )
        val m = adapter.toMatch(f)!!
        assertEquals("finished", m.status)
        @Suppress("UNCHECKED_CAST")
        val home = m.score!!["home"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val away = m.score!!["away"] as Map<String, Any?>
        assertEquals(listOf(6, 6, 7), home["sets"])
        assertEquals(listOf(2, 3, 6), away["sets"])
        assertNull(home["point"]) // "-" game result -> no current point
    }

    @Test
    fun `toTournament collapses draws by name, combined tier is ATP and WTA, dates span the matches`() {
        val group = listOf(
            FixtureDto(tournamentKey = "2155", tournamentName = "French Open", eventTypeType = "Atp Singles", eventDate = "2026-05-25"),
            FixtureDto(tournamentKey = "2156", tournamentName = "French Open", eventTypeType = "Wta Singles", eventDate = "2026-06-02"),
            FixtureDto(tournamentKey = "2281", tournamentName = "French Open", eventTypeType = "Boys Singles", eventDate = "2026-06-07"),
        )
        val t = adapter.toTournament("French Open", group)
        assertEquals("French Open", t.name)
        assertEquals("ATP & WTA", t.level) // collapsed across draws
        assertEquals("2155", t.externalId) // highest tier, then lowest key — stable id
        assertEquals(LocalDate.parse("2026-05-25"), t.startDate)
        assertEquals(LocalDate.parse("2026-06-07"), t.endDate)
    }

    @Test
    fun `isMainTourCategory treats ATP, WTA, and Grand Slam as main tour`() {
        assertTrue(adapter.isMainTourCategory("ATP"))
        assertTrue(adapter.isMainTourCategory("WTA"))
        assertTrue(adapter.isMainTourCategory("Grand Slam"))
        assertFalse(adapter.isMainTourCategory("Challenger"))
        assertFalse(adapter.isMainTourCategory("ITF"))
        assertFalse(adapter.isMainTourCategory(null))
    }

    @Test
    fun `capRecent keeps every main-tour match and caps only lower circuits`() {
        val base = Instant.parse("2026-06-01T00:00:00Z")
        fun match(id: String, category: String, start: Instant) = NormalizedMatch(
            externalId = id, status = "finished", category = category, startTime = start,
            player1 = NormalizedPlayerRef("${id}a", "P$id A", "WTA"),
            player2 = NormalizedPlayerRef("${id}b", "P$id B", "WTA"),
        )
        // one WTA semifinal that STARTED early, plus 100 later-starting ITF matches that would crowd out a global cap
        val wta = match("wta", "WTA", base)
        val itf = (1..100).map { match("itf$it", "ITF", base.plusSeconds(it * 60L)) }

        val result = adapter.capRecent(itf + wta)

        assertTrue(result.any { it.externalId == "wta" }, "main-tour match must be retained")
        assertEquals(40, result.count { it.category == "ITF" }, "lower circuits are capped")
        assertEquals(41, result.size)
        assertEquals(result.sortedByDescending { it.startTime }, result, "result is newest-start first")
    }

    @Test
    fun `maps a standing to a ranking with IOC country`() {
        val r = adapter.toRanking(
            StandingDto(place = "1", player = "Iga Swiatek", playerKey = "1910", league = "WTA", country = "Poland", points = "8501"),
            "WTA",
        )!!
        assertEquals(1, r.rank)
        assertEquals(8501, r.points)
        assertEquals("1910", r.player.externalId)
        assertEquals("WTA", r.player.tour)
        assertEquals("POL", r.player.countryCode)
        assertEquals(1, r.player.rankHint)
    }
}
