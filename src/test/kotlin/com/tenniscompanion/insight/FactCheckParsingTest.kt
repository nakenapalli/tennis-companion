package com.tenniscompanion.insight

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper

class FactCheckParsingTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `parses verdicts and surfaces only contradictions`() {
        val raw = """
            {"claims":[
              {"claim":"Alcaraz beats Sinner 6-3, 6-4","status":"supported","note":""},
              {"claim":"Sinner is ranked 1","status":"contradicted","note":"data says rank 2"},
              {"claim":"A big crowd attended","status":"unsupported","note":"not in data"}
            ]}
        """.trimIndent()
        val report = FactCheckParsing.parse(mapper, raw)
        assertEquals(3, report.claims.size)
        val contradictions = FactCheckParsing.contradictions(report)
        assertEquals(1, contradictions.size)
        assertEquals("Sinner is ranked 1", contradictions.first().claim)
    }

    @Test
    fun `strips a stray code fence`() {
        val report = FactCheckParsing.parse(mapper, "```json\n{\"claims\":[]}\n```")
        assertTrue(report.claims.isEmpty())
    }
}
