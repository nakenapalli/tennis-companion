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

    private val DIACRITICS = Regex("\\p{M}+")
    private val NON_ALNUM = Regex("[^a-z0-9 ]")
    private val WHITESPACE = Regex("\\s+")
}
