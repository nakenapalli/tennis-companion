package com.tenniscompanion.integration

import com.tenniscompanion.config.TennisApiProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient
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
