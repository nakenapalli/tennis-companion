package com.tenniscompanion.reconcile

import com.fasterxml.jackson.annotation.JsonProperty
import tools.jackson.databind.ObjectMapper

/**
 * Parses + validates the Tier-3 classifier's output (prompts §2.5). The prompt asks for a bare JSON
 * object; we strip a stray code fence defensively, then the caller checks the chosen id was one we
 * actually offered — a guard against the model inventing a player_id.
 */
object Tier3Parsing {

    /** The model's decision: a chosen candidate (or null = no match), a confidence, and a one-liner. */
    data class Tier3Decision(
        @JsonProperty("player_id") val playerId: Long?,
        val confidence: Double,
        val rationale: String = "",
    )

    fun stripFences(raw: String): String {
        var s = raw.trim()
        s = s.removePrefix("```json").removePrefix("```").trim()
        if (s.endsWith("```")) s = s.removeSuffix("```").trim()
        return s
    }

    fun parse(mapper: ObjectMapper, raw: String): Tier3Decision =
        mapper.readValue(stripFences(raw), Tier3Decision::class.java)

    /** Accept null (no match) or an id that was in the offered candidate set; reject invented ids. */
    fun isValid(decision: Tier3Decision, candidateIds: Set<Long>): Boolean =
        decision.playerId == null || decision.playerId in candidateIds
}
