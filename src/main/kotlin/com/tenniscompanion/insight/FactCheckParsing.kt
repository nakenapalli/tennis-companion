package com.tenniscompanion.insight

import tools.jackson.databind.ObjectMapper

/**
 * Parses the fact-checker's JSON output and surfaces contradictions. Mirrors `DigestParsing`: defensive
 * fence-stripping, then map onto Kotlin data classes.
 */
object FactCheckParsing {

    data class ClaimVerdict(val claim: String = "", val status: String = "", val note: String = "")

    data class FactCheckReport(val claims: List<ClaimVerdict> = emptyList())

    fun stripFences(raw: String): String {
        var s = raw.trim()
        s = s.removePrefix("```json").removePrefix("```").trim()
        if (s.endsWith("```")) s = s.removeSuffix("```").trim()
        return s
    }

    fun parse(mapper: ObjectMapper, raw: String): FactCheckReport =
        mapper.readValue(stripFences(raw), FactCheckReport::class.java)

    /** Claims the data actively contradicts — the gate for auto-publishing. */
    fun contradictions(report: FactCheckReport): List<ClaimVerdict> =
        report.claims.filter { it.status.equals("contradicted", ignoreCase = true) }
}
