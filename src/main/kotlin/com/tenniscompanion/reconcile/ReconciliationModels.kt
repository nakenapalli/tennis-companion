package com.tenniscompanion.reconcile

import org.springframework.boot.context.properties.ConfigurationProperties

/** The upstream player to resolve, plus whatever distinguishing signals the provider supplied. */
data class ReconciliationRequest(
    val source: String,
    val externalId: String,
    val externalName: String,
    val tour: String, // "ATP" | "WTA"
    val countryCode: String? = null,
    val rankHint: Int? = null,
    val birthYearHint: Int? = null,
)

/** Which tier produced a result (for auditing/metrics). */
enum class ReconciliationTier { CACHE, DETERMINISTIC, RULES, LLM, UNRESOLVED }

/**
 * Outcome. `playerId != null && confirmed` means resolved; otherwise it was written to the review
 * queue (unmapped) and serving must fall back to the upstream display name (design §6.4).
 */
data class ReconciliationResult(
    val playerId: Long?,
    val tier: ReconciliationTier,
    val confidence: Double,
    val confirmed: Boolean,
    val rationale: String,
)

@ConfigurationProperties(prefix = "app.reconcile")
data class ReconciliationProperties(
    /** At/above this, an auto-match is written as confirmed; below it goes to human review. */
    val confidenceThreshold: Double = 0.70,
    /** Tier 2: the score margin the top candidate must clear over the runner-up to auto-map. */
    val tier2Margin: Double = 1.0,
)
