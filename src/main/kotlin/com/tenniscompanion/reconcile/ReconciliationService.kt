package com.tenniscompanion.reconcile

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service

private data class CandidatePlayer(
    val playerId: Long,
    val firstName: String?,
    val lastName: String?,
    val countryCode: String?,
    val birthYear: Int?,
)

/**
 * Maps an upstream player onto a canonical Sackmann player via a tiered cascade (design §6.4),
 * stopping at the cheapest tier that resolves it:
 *   Tier 0 — confirmed cache hit (entity_map)
 *   Tier 1 — deterministic: unique normalized surname + first-name/initial match within the tour
 *   Tier 2 — rules scorer over the remaining candidates (country, birth-year)
 * Tier 3 (LLM) is added in Phase 6. Anything not confidently resolved is written unmapped and
 * queued for human review — it must never block serving (the caller falls back to the display name).
 */
@Service
class ReconciliationService(
    private val namedJdbc: NamedParameterJdbcTemplate,
    private val store: EntityMapStore,
    private val props: ReconciliationProperties,
) {

    fun resolve(req: ReconciliationRequest): ReconciliationResult {
        // Tier 0 — cache
        store.findConfirmed(req.source, req.externalId)?.let {
            return ReconciliationResult(it, ReconciliationTier.CACHE, 1.0, true, "Cached mapping")
        }

        val tokens = NameNormalizer.tokens(req.externalName)
        val candidates = if (tokens.isEmpty()) emptyList() else findCandidates(req.tour, tokens)
        val consistent = candidates.filter { firstNameConsistent(tokens, it.firstName) }

        val result = when {
            consistent.size == 1 -> ReconciliationResult(
                consistent.first().playerId, ReconciliationTier.DETERMINISTIC, 0.95, true,
                "Unique surname + first-name match within ${req.tour}",
            )
            consistent.size > 1 -> scoreCandidates(req, consistent)
            else -> ReconciliationResult(
                null, ReconciliationTier.UNRESOLVED, 0.2, false,
                if (candidates.isEmpty()) "No surname match in the historical set"
                else "Surname matched but no consistent first name",
            )
        }

        store.save(
            req.source, req.externalId, req.externalName,
            result.playerId, result.confidence, result.confirmed, result.tier.name, result.rationale,
        )
        return result
    }

    // Tier 2 — score the ambiguous candidates and require a clear winner.
    private fun scoreCandidates(req: ReconciliationRequest, candidates: List<CandidatePlayer>): ReconciliationResult {
        val ranked = candidates.map { it to score(req, it) }.sortedByDescending { it.second }
        val (best, bestScore) = ranked.first()
        val runnerUp = ranked.getOrNull(1)?.second ?: 0.0
        val margin = bestScore - runnerUp

        return if (bestScore > 0.0 && margin >= props.tier2Margin) {
            val confidence = (0.6 + bestScore * 0.1).coerceAtMost(0.9)
            ReconciliationResult(
                best.playerId, ReconciliationTier.RULES, confidence,
                confidence >= props.confidenceThreshold,
                "Disambiguated by signals (score=$bestScore, margin=$margin)",
            )
        } else {
            ReconciliationResult(
                null, ReconciliationTier.UNRESOLVED, 0.4, false,
                "Ambiguous: ${candidates.size} candidates, margin=$margin below ${props.tier2Margin}",
            )
        }
    }

    private fun score(req: ReconciliationRequest, c: CandidatePlayer): Double {
        var s = 0.0
        if (req.countryCode != null && c.countryCode != null &&
            req.countryCode.equals(c.countryCode, ignoreCase = true)
        ) {
            s += 2.0
        }
        if (req.birthYearHint != null && c.birthYear != null && req.birthYearHint == c.birthYear) s += 1.5
        return s
    }

    private fun firstNameConsistent(externalTokens: List<String>, candidateFirst: String?): Boolean {
        val cf = NameNormalizer.fold(candidateFirst ?: "")
        if (cf.isBlank()) return true
        val initial = cf.first()
        return externalTokens.any { it == cf || (it.length == 1 && it.first() == initial) }
    }

    private fun findCandidates(tour: String, surnameTokens: List<String>): List<CandidatePlayer> {
        val sql = """
            SELECT player_id, first_name, last_name, country_code,
                   EXTRACT(YEAR FROM birth_date)::int AS birth_year
            FROM players
            WHERE tour = :tour AND lower(unaccent(last_name)) IN (:surnames)
        """.trimIndent()
        val params = MapSqlParameterSource()
            .addValue("tour", tour)
            .addValue("surnames", surnameTokens)
        return namedJdbc.query(sql, params) { rs, _ ->
            CandidatePlayer(
                rs.getLong("player_id"),
                rs.getString("first_name"),
                rs.getString("last_name"),
                rs.getString("country_code"),
                (rs.getObject("birth_year") as? Number)?.toInt(),
            )
        }
    }
}
