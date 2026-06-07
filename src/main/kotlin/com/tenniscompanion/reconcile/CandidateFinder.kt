package com.tenniscompanion.reconcile

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component

/** A canonical Sackmann player surfaced as a possible match for an upstream name. */
data class PlayerCandidate(
    val playerId: Long,
    val firstName: String?,
    val lastName: String?,
    val countryCode: String?,
    val birthYear: Int?,
)

/**
 * Finds canonical players whose accent-folded surname matches one of an upstream name's tokens, within
 * a tour. Shared by the live cascade (Tiers 1–2 in [ReconciliationService]) and the offline Tier-3 LLM
 * classifier ([Tier3ReconciliationJob]) so the candidate set is built exactly one way.
 */
@Component
class CandidateFinder(private val namedJdbc: NamedParameterJdbcTemplate) {

    fun bySurname(tour: String, surnameTokens: List<String>): List<PlayerCandidate> {
        if (surnameTokens.isEmpty()) return emptyList()
        // Match the DB-side surname the SAME way NameNormalizer folds the upstream name: strip accents AND
        // punctuation to spaces, so a hyphen/space difference can't cause a miss. Pair with surnameKeys()
        // (singles + contiguous joins) so multi-word surnames are offered as keys. See NameNormalizer.
        val keys = NameNormalizer.surnameKeys(surnameTokens)
        val sql = """
            SELECT player_id, first_name, last_name, country_code,
                   EXTRACT(YEAR FROM birth_date)::int AS birth_year
            FROM players
            WHERE tour = :tour
              AND trim(regexp_replace(lower(unaccent(last_name)), '[^a-z0-9]+', ' ', 'g')) IN (:surnames)
        """.trimIndent()
        val params = MapSqlParameterSource()
            .addValue("tour", tour)
            .addValue("surnames", keys)
        return namedJdbc.query(sql, params) { rs, _ ->
            PlayerCandidate(
                rs.getLong("player_id"),
                rs.getString("first_name"),
                rs.getString("last_name"),
                rs.getString("country_code"),
                (rs.getObject("birth_year") as? Number)?.toInt(),
            )
        }
    }
}
