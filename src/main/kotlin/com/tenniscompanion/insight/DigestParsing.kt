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

    // --- article-context validation (Phase 7 news enrichment) ---

    /** Words for comparison: markdown links unwrapped to their text, lowercased, alphanumerics only. */
    private fun words(text: String): List<String> =
        text.replace(Regex("\\[([^\\]]*)\\]\\([^)]*\\)"), "$1") // [text](url) -> text
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .split(" ")
            .filter { it.isNotBlank() }

    private fun shingles(tokens: List<String>, n: Int): List<String> =
        if (tokens.size < n) emptyList() else (0..tokens.size - n).map { tokens.subList(it, it + n).joinToString(" ") }

    /**
     * Anti-plagiarism: any run of [n] consecutive words shared between the digest body and a supplied
     * source string (an article's title/summary). Paraphrases don't share long verbatim runs; copied
     * sentences do. A non-empty result should block the draft.
     */
    fun verbatimOverlaps(body: String, sources: List<String>, n: Int = 12): List<String> {
        val bodyWords = words(body)
        if (bodyWords.size < n) return emptyList()
        val sourceShingles = sources.flatMap { shingles(words(it), n) }.toHashSet()
        if (sourceShingles.isEmpty()) return emptyList()
        val hits = LinkedHashSet<String>()
        for (i in 0..bodyWords.size - n) {
            val shingle = bodyWords.subList(i, i + n).joinToString(" ")
            if (shingle in sourceShingles) hits += shingle
        }
        return hits.toList()
    }

    /** Markdown-link URLs in the body that were NOT among the supplied article URLs — invented citations. */
    fun fabricatedCitations(body: String, allowedUrls: Set<String>): List<String> {
        val allowed = allowedUrls.map { normalizeUrl(it) }.toHashSet()
        return Regex("\\[[^\\]]*\\]\\(([^)]+)\\)").findAll(body)
            .map { it.groupValues[1].trim() }
            .filter { normalizeUrl(it) !in allowed }
            .distinct()
            .toList()
    }

    private fun normalizeUrl(u: String): String = u.trim().substringBefore("?").trimEnd('/').lowercase()
}
