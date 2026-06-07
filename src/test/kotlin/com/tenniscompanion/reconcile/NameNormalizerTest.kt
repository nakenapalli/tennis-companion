package com.tenniscompanion.reconcile

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NameNormalizerTest {

    @Test
    fun `tokens fold accents, punctuation, and case`() {
        assertEquals(listOf("c", "alcaraz"), NameNormalizer.tokens("C. Alcaráz"))
        assertEquals(listOf("f", "auger", "aliassime"), NameNormalizer.tokens("F. Auger-Aliassime"))
    }

    @Test
    fun `surnameKeys adds contiguous joins so compound surnames can match`() {
        val keys = NameNormalizer.surnameKeys(listOf("f", "auger", "aliassime"))
        assertTrue(
            keys.containsAll(listOf("f", "auger", "aliassime", "auger aliassime", "f auger", "f auger aliassime")),
            "expected singles + contiguous joins, got $keys",
        )
    }

    @Test
    fun `surnameKeys leaves a single token alone`() {
        assertEquals(listOf("sinner"), NameNormalizer.surnameKeys(listOf("sinner")))
    }
}
