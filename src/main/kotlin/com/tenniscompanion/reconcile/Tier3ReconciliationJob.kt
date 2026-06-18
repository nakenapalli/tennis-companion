package com.tenniscompanion.reconcile

import com.fasterxml.jackson.annotation.JsonProperty
import com.tenniscompanion.config.LlmProperties
import com.tenniscompanion.insight.LlmClient
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/** Per-batch outcome counts, returned to the admin trigger. */
data class Tier3Summary(
    val examined: Int, // rows pulled from the queue
    val resolved: Int, // matched at/above the confidence threshold → confirmed
    val review: Int,   // matched but below threshold, or an invalid/failed response → still needs a human
    val noMatch: Int,  // model (or an empty candidate set) judged that no candidate fits
    val skipped: Int,  // couldn't rebuild candidates (e.g. a legacy row with no tour)
)

/** Serialized into the prompt's <external_entity> block. */
private data class Tier3ExternalEntity(
    val source: String,
    @JsonProperty("external_id") val externalId: String,
    val name: String?,
    val tour: String?,
    @JsonProperty("observed_context") val observedContext: Map<String, Any?>,
)

/**
 * Serialized into the prompt's <candidates> block. player_id maps to sackmann_id (stable integer)
 * so the LLM can reason about it and return it in its JSON response. After the LLM picks a
 * sackmann_id, we resolve the UUID from the players table before writing to entity_map.
 */
private data class Tier3CandidateView(
    @JsonProperty("player_id") val playerId: Long,
    val name: String,
    val country: String?,
    @JsonProperty("birth_year") val birthYear: Int?,
)

/**
 * Tier 3 of the reconciliation cascade (design §6.4): an OFFLINE batch over the human-review queue,
 * reached only for rows Tiers 0–2 left unresolved. For each row it rebuilds the same surname candidate
 * set the live cascade used, asks the LLM (Haiku) to pick one candidate or none with a confidence,
 * validates the choice was one we actually offered, and writes the result back to entity_map.
 *
 * Runs on a schedule (default daily 07:00 UTC) and on-demand via `POST /api/admin/reconcile/tier3`;
 * never on the hot poll path. Gated on `app.llm.enabled` + a key (plus `app.reconcile.tier3-enabled`
 * for the scheduled run). A confirmed match here becomes a free Tier-0 cache hit next time; anything
 * below threshold stays for a human.
 */
@Component
class Tier3ReconciliationJob(
    private val store: EntityMapStore,
    private val candidates: CandidateFinder,
    private val llm: LlmClient,
    private val props: LlmProperties,
    private val reconProps: ReconciliationProperties,
    private val mapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Scheduled batch — default daily 07:00 UTC, after the morning rankings/tournament jobs have
     * refreshed the review queue. No-ops unless Tier-3 scheduling is enabled and the LLM key is set;
     * batch size is `app.reconcile.tier3-batch-size`.
     */
    @Scheduled(cron = "\${app.reconcile.tier3-cron:0 0 7 * * *}")
    fun scheduled() {
        if (reconProps.tier3Enabled && props.enabled && props.effectiveKey.isNotBlank()) {
            run(reconProps.tier3BatchSize)
        }
    }

    fun run(limit: Int = 50): Tier3Summary {
        if (!props.enabled || props.effectiveKey.isBlank()) {
            log.info("Tier 3 reconciliation skipped: LLM disabled or no API key")
            return Tier3Summary(0, 0, 0, 0, 0)
        }
        val queue = store.unresolvedForTier3(limit)
        var resolved = 0
        var review = 0
        var noMatch = 0
        var skipped = 0

        for (row in queue) {
            val tour = row.tour
            val tokens = NameNormalizer.tokens(row.externalName ?: "")
            if (tour == null || tokens.isEmpty()) {
                skipped++
                continue
            }

            // Only candidates with a sackmann_id are usable for LLM prompts (need a stable integer id).
            val cands = candidates.bySurname(tour, tokens).filter { it.sackmannId != null }
            if (cands.isEmpty()) {
                // Surname isn't in the historical set — nothing to choose from. Mark it LLM-examined
                // (rationale explains why) so the next batch doesn't keep re-picking it.
                writeBack(row, tour, null, null, false, "No historical candidates by surname")
                noMatch++
                continue
            }

            val decision = classify(row, tour, cands)
            val candidateIds = cands.mapNotNullTo(HashSet()) { it.sackmannId }

            when {
                decision == null || !Tier3Parsing.isValid(decision, candidateIds) -> {
                    val why = if (decision == null) "LLM call/parse failed" else "LLM returned an id outside the candidate set"
                    writeBack(row, tour, null, decision?.confidence, false, why)
                    review++
                }
                decision.playerId == null -> {
                    writeBack(row, tour, null, decision.confidence, false, decision.rationale)
                    noMatch++
                }
                else -> {
                    val confirmed = decision.confidence >= reconProps.confidenceThreshold
                    // Resolve sackmann_id → UUID before persisting (entity_map.player_id is UUID)
                    val playerUuid = store.playerUuidBySackmannId(decision.playerId)
                    writeBack(row, tour, playerUuid, decision.confidence, confirmed, decision.rationale)
                    if (confirmed) resolved++ else review++
                }
            }
        }

        val summary = Tier3Summary(queue.size, resolved, review, noMatch, skipped)
        log.info("Tier 3 batch complete: {}", summary)
        return summary
    }

    private fun classify(row: ReconcileQueueRow, tour: String, cands: List<PlayerCandidate>): Tier3Parsing.Tier3Decision? {
        // Only the signals we actually have — omit rather than guess (prompts §2.1).
        val observed = buildMap<String, Any?> {
            row.countryCode?.let { put("country", it) }
            row.rankHint?.let { put("current_rank_hint", it) }
        }
        val external = Tier3ExternalEntity(row.source, row.externalPlayerId, row.externalName, tour, observed)
        val views = cands.map {
            Tier3CandidateView(
                playerId = it.sackmannId!!,
                name = listOfNotNull(it.firstName, it.lastName).joinToString(" ").trim(),
                country = it.countryCode,
                birthYear = it.birthYear,
            )
        }
        return try {
            val raw = llm.complete(
                Tier3Prompts.SYSTEM,
                Tier3Prompts.user(mapper.writeValueAsString(external), mapper.writeValueAsString(views)),
                props.tier3Model,
                MAX_TOKENS,
            )
            Tier3Parsing.parse(mapper, raw)
        } catch (e: Exception) {
            log.warn("Tier 3 classify failed for {}:{} — {}", row.source, row.externalPlayerId, e.message)
            null
        }
    }

    private fun writeBack(
        row: ReconcileQueueRow,
        tour: String,
        playerId: UUID?,
        confidence: Double?,
        confirmed: Boolean,
        rationale: String,
    ) = store.save(
        row.source, row.externalPlayerId, row.externalName,
        playerId, confidence, confirmed, ReconciliationTier.LLM.name, rationale,
        tour = tour, countryCode = row.countryCode, rankHint = row.rankHint,
    )

    companion object {
        private const val MAX_TOKENS = 512 // a small JSON object + one-sentence rationale
    }
}
