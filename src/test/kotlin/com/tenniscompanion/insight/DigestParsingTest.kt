package com.tenniscompanion.insight

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DigestParsingTest {

    @Test
    fun `stripFences removes a json code fence`() {
        assertEquals("{\"a\":1}", DigestParsing.stripFences("```json\n{\"a\":1}\n```"))
        assertEquals("{\"a\":1}", DigestParsing.stripFences("```\n{\"a\":1}\n```"))
        assertEquals("{\"a\":1}", DigestParsing.stripFences("  {\"a\":1}  ")) // no fence, just trimmed
    }

    @Test
    fun `ungroundedEntities flags names absent from the fact sheet`() {
        val names = setOf("Carlos Alcaraz", "Roland Garros")
        val body = "Carlos Alcaraz shines at Roland Garros, but Roger Federer is not here.\n\n## The Headline Quarterfinal"
        val flagged = DigestParsing.ungroundedEntities(body, names)
        assertEquals(listOf("Roger Federer"), flagged) // grounded names + editorial heading are not flagged
    }

    @Test
    fun `ungroundedEntities is empty when every entity is grounded`() {
        val names = setOf("Aryna Sabalenka", "Naomi Osaka")
        val body = "Aryna Sabalenka faces Naomi Osaka — a rematch worth watching."
        assertTrue(DigestParsing.ungroundedEntities(body, names).isEmpty())
    }

    @Test
    fun `verbatimOverlaps flags a copied run but not a paraphrase`() {
        val src = "Carlos Alcaraz produced a stunning display of clay court tennis to overwhelm his opponent in straight sets today"
        val copied = "In Paris, Carlos Alcaraz produced a stunning display of clay court tennis to overwhelm his opponent in straight sets."
        val paraphrase = "Alcaraz looked excellent on the clay and beat his rival comfortably in Paris."
        assertTrue(DigestParsing.verbatimOverlaps(copied, listOf(src)).isNotEmpty(), "copied phrasing should be flagged")
        assertTrue(DigestParsing.verbatimOverlaps(paraphrase, listOf(src)).isEmpty(), "a paraphrase should pass")
    }

    @Test
    fun `fabricatedCitations flags links to sources not supplied`() {
        val allowed = setOf("https://example.com/a")
        val body = "Alcaraz wins ([Tennis News](https://example.com/a)). Big upset ([Other](https://evil.com/x))."
        assertEquals(listOf("https://evil.com/x"), DigestParsing.fabricatedCitations(body, allowed))
    }
}
