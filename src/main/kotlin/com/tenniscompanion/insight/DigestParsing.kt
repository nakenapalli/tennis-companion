package com.tenniscompanion.insight

import com.fasterxml.jackson.annotation.JsonProperty
import tools.jackson.databind.ObjectMapper

/** The model's structured output: a headline + a markdown body. */
data class DigestResult(val title: String, @JsonProperty("body_markdown") val bodyMarkdown: String)

/**
 * Defensive parsing + the cheap grounding check (design §9 / prompts §1.5). The prompt asks for raw
 * JSON, but we strip a stray ```json fence just in case, then validate that capitalized name-like
 * phrases in the body actually appear in the fact sheet — a flag for human review, not a hard gate.
 */
object DigestParsing {

    fun stripFences(raw: String): String {
        var s = raw.trim()
        s = s.removePrefix("```json").removePrefix("```").trim()
        if (s.endsWith("```")) s = s.removeSuffix("```").trim()
        return s
    }

    fun parse(mapper: ObjectMapper, raw: String): DigestResult =
        mapper.readValue(stripFences(raw), DigestResult::class.java)

    // Editorial/structural words that look like names but aren't entities — keeps section headings
    // ("## Highlight Matches", "## Top Men's Results") and common roundup vocabulary from being flagged.
    private val EDITORIAL = setOf(
        "the", "this", "week", "weekly", "why", "watch", "watching", "worth", "what", "clay", "grass",
        "hard", "court", "tour", "match", "matches", "matchup", "matchups", "final", "finals", "semifinal",
        "semifinals", "quarterfinal", "quarterfinals", "round", "draw", "seed", "seeds", "title", "story",
        "headline", "stakes", "preview", "must", "and", "for", "highlight", "highlights", "result", "results",
        "men", "mens", "women", "womens", "top", "notable", "upset", "upsets", "recent", "roundup",
        "tournament", "tournaments", "today", "yesterday", "key", "wins", "results",
    )

    /**
     * Capitalized multi-word phrases in the body whose words are all absent from the fact sheet (and
     * aren't editorial filler) — i.e. likely ungrounded entities. Cheap heuristic, advisory only.
     */
    fun ungroundedEntities(body: String, names: Set<String>): List<String> {
        val tokens = names.flatMap { it.lowercase().split(Regex("[^a-z0-9]+")) }.filter { it.length >= 3 }.toSet()
        return Regex("[A-Z][\\p{L}.'’-]+(?:\\s+[A-Z][\\p{L}.'’-]+)+").findAll(body)
            .map { it.value.trim() }
            .filter { phrase ->
                val words = phrase.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 }
                words.isNotEmpty() && words.none { it in tokens || it in EDITORIAL }
            }
            .distinct()
            .toList()
    }
}
