package com.tenniscompanion.reconcile

import com.tenniscompanion.TestcontainersConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@TestPropertySource(properties = ["app.poll.enabled=false"])
class ReconciliationServiceTest(
    @Autowired val service: ReconciliationService,
    @Autowired val jdbc: JdbcTemplate,
    @Autowired val store: EntityMapStore,
) {
    // UUIDs populated after seed() by querying back the generated ids
    private lateinit var alcarazEspUuid: UUID
    private lateinit var alcarazArgUuid: UUID
    private lateinit var sinnerUuid: UUID
    private lateinit var zverevUuid: UUID
    private lateinit var augUuid: UUID

    @BeforeEach
    fun seed() {
        jdbc.update("DELETE FROM entity_map")
        jdbc.update("DELETE FROM players")
        insert(207989, "Carlos", "Alcaraz", "ESP", "2003-05-05") // the real Alcaraz
        insert(144750, "Carlos", "Alcaraz", "ARG", "1996-01-01") // a fabricated same-name collision
        insert(206173, "Jannik", "Sinner", "ITA", "2001-08-16")
        insert(100644, "Alexander", "Zverev", "GER", "1997-04-20")
        insert(220000, "Felix", "Auger-Aliassime", "CAN", "2000-08-08") // hyphenated compound surname

        fun uuid(sackmannId: Long): UUID = jdbc.queryForObject(
            "SELECT id FROM players WHERE sackmann_id = ?", UUID::class.java, sackmannId,
        )!!
        alcarazEspUuid = uuid(207989)
        alcarazArgUuid = uuid(144750)
        sinnerUuid = uuid(206173)
        zverevUuid = uuid(100644)
        augUuid = uuid(220000)
    }

    private fun insert(sackmannId: Long, first: String, last: String, country: String, dob: String) {
        jdbc.update(
            "INSERT INTO players(sackmann_id, source_player_id, first_name, last_name, country_code, birth_date, tour) " +
                "VALUES (?,?,?,?,?,?::date,'ATP')",
            sackmannId, sackmannId, first, last, country, dob,
        )
    }

    private fun req(extId: String, name: String, country: String? = null) =
        ReconciliationRequest(source = "prov", externalId = extId, externalName = name, tour = "ATP", countryCode = country)

    @Test
    fun `tier 1 resolves a unique full-name match`() {
        val r = service.resolve(req("x1", "Jannik Sinner"))
        assertEquals(sinnerUuid, r.playerId)
        assertEquals(ReconciliationTier.DETERMINISTIC, r.tier)
        assertTrue(r.confirmed)
    }

    @Test
    fun `tier 1 handles an initial`() {
        val r = service.resolve(req("x2", "A. Zverev"))
        assertEquals(zverevUuid, r.playerId)
    }

    @Test
    fun `tier 1 resolves a hyphenated compound surname`() {
        val r = service.resolve(req("x8", "F. Auger-Aliassime"))
        assertEquals(augUuid, r.playerId)
        assertEquals(ReconciliationTier.DETERMINISTIC, r.tier)
    }

    @Test
    fun `tier 1 resolves a multi-word surname given with a space`() {
        val r = service.resolve(req("x9", "Felix Auger Aliassime"))
        assertEquals(augUuid, r.playerId)
    }

    @Test
    fun `tier 2 disambiguates a collision by country`() {
        val r = service.resolve(req("x3", "Carlos Alcaraz", country = "ESP"))
        assertEquals(alcarazEspUuid, r.playerId)
        assertEquals(ReconciliationTier.RULES, r.tier)
        assertTrue(r.confirmed)
    }

    @Test
    fun `ambiguous collision with no signal is queued for review`() {
        val r = service.resolve(req("x4", "Carlos Alcaraz"))
        assertNull(r.playerId)
        assertFalse(r.confirmed)
        assertTrue(store.unmapped(10).any { it.externalPlayerId == "x4" })
    }

    @Test
    fun `unknown player is queued for review`() {
        val r = service.resolve(req("x5", "Random Qualifier"))
        assertNull(r.playerId)
        assertEquals(ReconciliationTier.UNRESOLVED, r.tier)
    }

    @Test
    fun `tier 0 returns the cached mapping regardless of name`() {
        service.resolve(req("x6", "Jannik Sinner")) // writes a confirmed mapping
        jdbc.update("UPDATE players SET last_name = 'CHANGED' WHERE sackmann_id = 206173")
        val r = service.resolve(req("x6", "Totally Different Name"))
        assertNotNull(r.playerId)
        assertEquals(sinnerUuid, r.playerId)
        assertEquals(ReconciliationTier.CACHE, r.tier)
    }
}
