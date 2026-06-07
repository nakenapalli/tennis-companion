package com.tenniscompanion.reconcile

import java.text.Normalizer

/**
 * Folds names for comparison: strips accents, lowercases, drops punctuation (hyphens, apostrophes,
 * the dots in "C."), and collapses whitespace. "C. Alcaraz", "Carlos Alcaraz", and "Carlos Alcaráz"
 * all fold to comparable token sets. `object` is an idiomatic Kotlin singleton.
 */
object NameNormalizer {

    fun fold(raw: String): String =
        Normalizer.normalize(raw.trim(), Normalizer.Form.NFD)
            .replace(DIACRITICS, "")
            .lowercase()
            .replace(NON_ALNUM, " ")
            .replace(WHITESPACE, " ")
            .trim()

    /** Folded whitespace-separated tokens, e.g. "C. Alcaraz" -> ["c", "alcaraz"]. */
    fun tokens(raw: String): List<String> = fold(raw).split(" ").filter { it.isNotBlank() }

    /**
     * Surname blocking keys for the candidate query: every single token PLUS every *contiguous* multi-token
     * run (e.g. ["f","auger","aliassime"] -> also "auger aliassime", "f auger", "f auger aliassime"). Many
     * tennis surnames are multi-word ("de Minaur", "Auger-Aliassime", "Carreño Busta"), but [tokens] splits
     * them — so without the joined keys a compound surname can never match a multi-word `last_name`. A joined
     * key contains a space and can only match a multi-word surname, so single-word names are unaffected.
     */
    fun surnameKeys(tokens: List<String>): List<String> {
        if (tokens.size <= 1) return tokens
        val keys = LinkedHashSet(tokens)
        for (start in tokens.indices) {
            for (end in start + 1 until tokens.size) {
                keys.add(tokens.subList(start, end + 1).joinToString(" "))
            }
        }
        return keys.toList()
    }

    private val DIACRITICS = Regex("\\p{M}+")
    private val NON_ALNUM = Regex("[^a-z0-9 ]")
    private val WHITESPACE = Regex("\\s+")
}
