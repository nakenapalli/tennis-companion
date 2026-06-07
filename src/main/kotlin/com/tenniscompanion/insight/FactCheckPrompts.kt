package com.tenniscompanion.insight

/**
 * Post-generation fact-check prompts. The model verifies the generated digest's HARD facts (scores,
 * winners, rankings, rounds, H2H) against the same DB fact sheet it was grounded on — given whole, since
 * the fact sheet is small and complete (no retrieval needed). Article-attributed context (inline-cited
 * claims) is out of scope. Output is strict JSON of per-claim verdicts.
 */
object FactCheckPrompts {

    const val SYSTEM = """You are a fact-checker for a tennis app. You verify a published summary against
the authoritative data it was supposed to be built from.

You are given:
- <summary>: the published roundup (markdown).
- <data>: the authoritative facts (tournaments, matchups with exact "result" strings, rankings, H2H).

Do this:
- Extract each concrete factual claim in the summary about results, scores, winners/losers, rankings,
  seeds, rounds, and head-to-head records.
- For each claim, find the matching item in <data> and assign a status:
  - "supported": the data confirms the claim.
  - "contradicted": the data states something different (wrong winner, score, rank, round, etc.).
  - "unsupported": the claim is not found in the data at all.
- IGNORE narrative/context claims that carry an inline citation (a markdown link) — those come from news
  articles, not this data, and are out of scope. Do not judge them.
- Judge ONLY against <data>. Never use outside knowledge.

Output:
- A single JSON object and nothing else (no prose, no code fences).
- Shape: { "claims": [ { "claim": string, "status": "supported"|"contradicted"|"unsupported", "note": string } ] }
- "note" briefly explains a contradicted/unsupported verdict (cite what the data actually says)."""

    private const val USER_TEMPLATE = """Fact-check this summary against the data.

<summary>
{{SUMMARY}}
</summary>

<data>
{{DATA_JSON}}
</data>

Respond with only the JSON object."""

    fun user(summaryMarkdown: String, dataJson: String): String =
        USER_TEMPLATE.replace("{{SUMMARY}}", summaryMarkdown).replace("{{DATA_JSON}}", dataJson)
}
