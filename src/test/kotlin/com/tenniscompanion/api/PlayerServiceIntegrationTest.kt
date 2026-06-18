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
import java.util.UUID

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
    private val uuid1 = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val uuid2 = UUID.fromString("00000000-0000-0000-0000-000000000002")

    @BeforeEach
    fun seed() {
        jdbc.update("DELETE FROM matches")
        jdbc.update("DELETE FROM rankings")
        jdbc.update("DELETE FROM players")
        jdbc.update(
            "INSERT INTO players(id, sackmann_id, source_player_id, first_name, last_name, hand, country_code, height_cm, tour) " +
                "VALUES (?::uuid,1,1,'Carlos','Alcaraz','R','ESP',183,'ATP'), (?::uuid,2,2,'Jannik','Sinner','R','ITA',188,'ATP')",
            uuid1.toString(), uuid2.toString(),
        )
        // two ranking rows for player 1 — the later date should win
        jdbc.update("INSERT INTO rankings(source, ranking_date, player_id, rank, points, tour) VALUES ('sackmann','2026-01-01',?::uuid,3,7000,'ATP')", uuid1.toString())
        jdbc.update("INSERT INTO rankings(source, ranking_date, player_id, rank, points, tour) VALUES ('sackmann','2026-05-25',?::uuid,2,8000,'ATP')", uuid1.toString())
        // player 1 wins the older match; player 2 wins the newer one
        jdbc.update(
            "INSERT INTO matches(source, status, tour, tourney_id, tourney_name, surface, tourney_date, match_num, round, best_of, " +
                "winner_id, loser_id, winner_name, loser_name, player1_id, player2_id, player1_name, player2_name, score) " +
                "VALUES ('sackmann','finished','ATP','2025-560','US Open','Hard','2025-08-25',1,'F',5," +
                "?::uuid,?::uuid,'Carlos Alcaraz','Jannik Sinner',?::uuid,?::uuid,'Carlos Alcaraz','Jannik Sinner','6-2 3-6 6-1 6-4')",
            uuid1.toString(), uuid2.toString(), uuid1.toString(), uuid2.toString(),
        )
        jdbc.update(
            "INSERT INTO matches(source, status, tour, tourney_id, tourney_name, surface, tourney_date, match_num, round, best_of, " +
                "winner_id, loser_id, winner_name, loser_name, player1_id, player2_id, player1_name, player2_name, score) " +
                "VALUES ('sackmann','finished','ATP','2025-605','Tour Finals','Hard','2025-11-09',1,'F',3," +
                "?::uuid,?::uuid,'Jannik Sinner','Carlos Alcaraz',?::uuid,?::uuid,'Jannik Sinner','Carlos Alcaraz','7-6 7-5')",
            uuid2.toString(), uuid1.toString(), uuid2.toString(), uuid1.toString(),
        )
    }

    @Test
    fun `profile returns the most recent rank`() {
        val p = service.profile(uuid1)
        assertNotNull(p)
        assertEquals("Alcaraz", p!!.lastName)
        assertEquals(2, p.currentRank)
        assertEquals(LocalDate.parse("2026-05-25"), p.currentRankDate)
    }

    @Test
    fun `unknown player resolves to null`() {
        assertNull(service.profile(UUID.randomUUID()))
    }

    @Test
    fun `recent matches are newest-first and from the subject's perspective`() {
        val m = service.recentMatches(uuid1, 10)
        assertEquals(2, m.size)
        assertEquals("L", m[0].result) // newest = Tour Finals loss
        assertEquals("Jannik Sinner", m[0].opponentName)
        assertEquals("W", m[1].result) // US Open win
    }

    @Test
    fun `head-to-head tallies both sides`() {
        val h = service.headToHead(uuid1, uuid2)
        assertEquals(1, h.playerWins)
        assertEquals(1, h.opponentWins)
        assertEquals(2, h.matches.size)
    }
}
