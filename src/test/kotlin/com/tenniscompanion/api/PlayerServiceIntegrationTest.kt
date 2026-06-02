package com.tenniscompanion.api

import com.tenniscompanion.TestcontainersConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import java.time.LocalDate

/**
 * Exercises the serving path against the real Flyway schema on a throwaway Postgres (Testcontainers).
 * Seeds a tiny fixture, then asserts profile/recent-matches/h2h behave from the subject's perspective.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@TestPropertySource(properties = ["app.poll.enabled=false"]) // don't hit the live feed during tests
class PlayerServiceIntegrationTest(
    @org.springframework.beans.factory.annotation.Autowired val service: PlayerService,
    @org.springframework.beans.factory.annotation.Autowired val jdbc: JdbcTemplate,
) {

    @BeforeEach
    fun seed() {
        jdbc.update("DELETE FROM matches")
        jdbc.update("DELETE FROM rankings_history")
        jdbc.update("DELETE FROM players")
        jdbc.update(
            "INSERT INTO players(player_id, source_player_id, first_name, last_name, hand, country_code, height_cm, tour) " +
                "VALUES (1,1,'Carlos','Alcaraz','R','ESP',183,'ATP'), (2,2,'Jannik','Sinner','R','ITA',188,'ATP')",
        )
        // two ranking rows for player 1 — the later date should win
        jdbc.update("INSERT INTO rankings_history(ranking_date, player_id, rank, points, tour) VALUES ('2026-01-01',1,3,7000,'ATP')")
        jdbc.update("INSERT INTO rankings_history(ranking_date, player_id, rank, points, tour) VALUES ('2026-05-25',1,2,8000,'ATP')")
        // player 1 wins the older match; player 2 wins the newer one
        jdbc.update(
            "INSERT INTO matches(tour, tourney_id, tourney_name, surface, tourney_date, match_num, round, best_of, winner_id, loser_id, winner_name, loser_name, score) " +
                "VALUES ('ATP','2025-560','US Open','Hard','2025-08-25',1,'F',5,1,2,'Carlos Alcaraz','Jannik Sinner','6-2 3-6 6-1 6-4')",
        )
        jdbc.update(
            "INSERT INTO matches(tour, tourney_id, tourney_name, surface, tourney_date, match_num, round, best_of, winner_id, loser_id, winner_name, loser_name, score) " +
                "VALUES ('ATP','2025-605','Tour Finals','Hard','2025-11-09',1,'F',3,2,1,'Jannik Sinner','Carlos Alcaraz','7-6 7-5')",
        )
    }

    @Test
    fun `profile returns the most recent rank`() {
        val p = service.profile(1)
        assertNotNull(p)
        assertEquals("Alcaraz", p!!.lastName)
        assertEquals(2, p.currentRank)
        assertEquals(LocalDate.parse("2026-05-25"), p.currentRankDate)
    }

    @Test
    fun `unknown player resolves to null`() {
        assertNull(service.profile(999))
    }

    @Test
    fun `recent matches are newest-first and from the subject's perspective`() {
        val m = service.recentMatches(1, 10)
        assertEquals(2, m.size)
        assertEquals("L", m[0].result) // newest = Tour Finals loss
        assertEquals("Jannik Sinner", m[0].opponentName)
        assertEquals("W", m[1].result) // US Open win
    }

    @Test
    fun `head-to-head tallies both sides`() {
        val h = service.headToHead(1, 2)
        assertEquals(1, h.playerWins)
        assertEquals(1, h.opponentWins)
        assertEquals(2, h.matches.size)
    }
}
