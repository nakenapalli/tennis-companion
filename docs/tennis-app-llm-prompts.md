# Tennis Companion — LLM Prompt Specifications

> Companion to `tennis-app-design-doc.md`. Defines the two LLM interaction points in the MVP: the **weekly digest generator** (§9 of the design doc) and the **Tier 3 entity reconciliation classifier** (§6.4). Both are invoked only from scheduled backend jobs through the `LlmClient` interface — never per user request.
>
> **Core principle shared by both:** the model is given real data and asked to *reason over it or write around it*. It must never supply facts (names, numbers, records) from its own memory. Every prompt below enforces this in its wording, and the calling code validates the output against the supplied data (see "Validation" in each section).

---

## Conventions

- Prompts are shown as a **system** message plus a **user** message. Send both on every call; they are stateless (no conversation history).
- `{{DOUBLE_BRACE}}` tokens are placeholders the calling code substitutes before sending.
- Input data is delimited with XML-style tags inside the user message. This is deliberate — tag-delimited data is easier for the model to attend to and harder to confuse with instructions than free-form text.
- Both prompts request **strict JSON output** so the result maps directly onto a Kotlin data class. Instruct the model to emit *only* the JSON object with no surrounding prose or markdown fences; the calling code should still defensively strip a leading/trailing ```json fence before parsing, in case one appears.

---

## 1. Weekly digest generator

Produces the "what's worth watching this week and why" editorial piece. Input is a **fact sheet** assembled from Postgres; output is a title + markdown body to store in `generated_insights`.

> **News context (as-built).** The user prompt also includes an `<articles>` block — recent **full** tennis articles (title, author, publication, url, body) **scraped** from manually-chosen sites (`ScrapedNewsSource` + a `SiteScraper` per site, e.g. `TennisDotComScraper`) — for context and to model the natural voice of tennis writing. Rules enforced in the system prompt: the fact sheet stays authoritative for all scores/names (no citation needed); the model must **reword everything (never copy)** and **cite any article-sourced fact inline** as a markdown link `([Publication](url))`, citing only supplied sources. Post-generation validation (`DigestParsing`): `verbatimOverlaps` blocks copied phrasing (one stricter retry, then abort), `fabricatedCitations` + `ungroundedEntities` are advisory. **Articles are used transiently and never persisted** (`source_data` holds only the DB fact sheet). The digest is news-enriched: if no article can be scraped, the run is skipped.
>
> **Fact-check + auto-publish (as-built).** After generation a second LLM pass (`FactCheckPrompts`/`FactCheckParsing`) verifies the digest's hard facts (scores/winners/rankings/rounds) against the **whole** fact sheet — small and complete, so no retrieval is needed; cited article context is out of scope. Clean → the job **auto-publishes**; any contradiction (or a failed check) → it stays `DRAFT`. The home page embeds only published digests.

### 1.1 Fact sheet schema (assembled by `WeeklyDigestJob`, passed into the prompt)

Every value here is a real value queried from the database. The model is told it may use *only* what appears in this object. **As-built shape** (from `insight/FactSheetBuilder`):

```json
{
  "week_of": "2026-06-02",
  "tournaments": [
    { "name": "French Open", "level": "ATP & WTA", "surface": null, "location": null, "starts": "2026-05-26", "ends": "2026-06-03" }
  ],
  "top_players": {
    "ATP": [ { "name": "Jannik Sinner", "rank": 1, "country": "ITA" } ],
    "WTA": [ { "name": "Aryna Sabalenka", "rank": 1, "country": null } ]
  },
  "notable_matchups": [
    {
      "result": "Marta Kostyuk beats Elina Svitolina 6-3, 2-6, 6-2",
      "round": "Quarterfinal",
      "tournament": "French Open",
      "winner": { "name": "Marta Kostyuk", "rank": 15 },
      "loser":  { "name": "Elina Svitolina", "rank": 7 },
      "h2h": "Marta Kostyuk leads 1-0",
      "context": "previous meeting: 2024 Toronto, Marta Kostyuk won 6-2 2-6 6-2"
    }
  ],
  "player_form": [
    { "name": "Marta Kostyuk", "recent_results": [ "W vs Mirra Andreeva 6-3 7-5 (Final)" ] }
  ]
}
```

Notes for the job assembling this (these were learned the hard way — the model invents/misreads anything left ambiguous):
- **`notable_matchups` are FINISHED matches only**, and each is pre-resolved into unambiguous facts so the model never has to infer who won, the set order, or the stage:
  - `result` is the **single source of truth**: `"<winner> beats <loser> <score>"`, winner-first, **every set, comma-separated** (derived from the per-set scores).
  - `winner`/`loser` carry the names + ranks (names come from the reconciled Sackmann profile, not the feed's "C. Alcaraz" display string).
  - `round` is **cleaned to plain English** ("Round of 16", "Quarterfinal", "Final") from the upstream's mixed `1/N-finals` / word forms, and **omitted entirely** when it can't be parsed (so there's no stage to misuse).
  - `h2h` is a **plain pre-built string** ("X leads 2-1" / "First career meeting" / "Tied 2-2"); `context` carries the previous-meeting line. All derived from `matches`.
- Keep the fact sheet tight — a few tournaments, a few matchups, a few form lines. Smaller + higher-quality beats an exhaustive dump.

### 1.2 System message

> The original spec asked for a warm 250–400-word editorial; in practice that read as overwrought and the model embellished facts. Per user feedback this was tuned to a **concise, sectioned, present-tense results roundup with strict accuracy rules**. The as-built prompt (`insight/DigestPrompts.kt`):

```
You are an editor for a tennis app that helps fans keep up with the sport.
You write a brief, scannable weekly roundup of what's worth knowing.

Absolute rules about facts:
- You may use ONLY the information provided in the <fact_sheet> supplied by the user.
- Every player name, tournament name, ranking, seeding, score, and head-to-head
  record you mention MUST appear in the fact sheet. Do not introduce any player,
  match, statistic, or record that is not present there.
- If you want to make a point that the fact sheet does not support, leave it out.
  Never invent or estimate a statistic. Never rely on your own background knowledge
  of players or results.

Accuracy (do not get these wrong):
- Each matchup includes a "result" string (e.g. "Arnaldi beats Tiafoe 6-7, 7-6, 6-3, 6-7, 6-4").
  State the winner and the score exactly as written there — do not omit, reorder, or alter any set.
- Do NOT describe the shape or trajectory of a match — no "comeback", "after dropping a set",
  "in straight sets", "battled back". Just report who wins and the final score.
- Use a matchup's "round" only if it is given, and verbatim. NEVER name or infer a stage
  ("quarterfinal", "last eight", "to reach the last eight") that the fact sheet does not state.

Style and structure:
- Plain and factual. Lead with the result; add at most a short clause on why it matters (ranking gap,
  head-to-head). Use ordinary verbs — beats, knocks out, bests, wins, loses, advances. Avoid dramatic or
  flowery verbs like dismantled, dispatched, demolished, storms, survives, cruises.
- Write results in the present tense — "Arnaldi eliminates Tiafoe", not "eliminated".
- Organize the body into a few markdown sections ("## " headings) with bullet points ("- "). YOU choose
  the sections that fit the data and how much of it there is — e.g. "Top Men's Results",
  "Top Women's Results", "Notable Upsets", "Tournaments This Week". Omit any section the facts can't support.
- Mention each result or player only once — put it in the single section where it fits best.
- One tight sentence per bullet. A few sections, a few bullets each. Prefer fewer, higher-signal
  bullets over an exhaustive list. Don't pad to fill space.

Output format:
- Respond with a single JSON object and nothing else: no prose before or after,
  no markdown code fences.
- Shape: { "title": string, "body_markdown": string }
- "title" is a plain, natural label of the week's content — e.g. "French Open: Round of 16 Results" or
  "This Week at the French Open". No more than ~8 words. Don't imply cause-and-effect, cram two ideas
  together, or use awkward phrasings.
- "body_markdown" is the roundup as markdown using "## " section headings and "- " bullets.
```

### 1.3 User message

```
Write this week's tennis roundup using only the facts below.

<fact_sheet>
{{FACT_SHEET_JSON}}
</fact_sheet>

Remember: every name, number, and record must come from the fact sheet above.
Respond with only the JSON object.
```

### 1.4 Expected output

```json
{
  "title": "French Open: Women's Quarterfinal Results",
  "body_markdown": "## Top Women's Results\n\n- Marta Kostyuk (No. 15) beats Elina Svitolina (No. 7) 6-3, 2-6, 6-2 in the quarterfinal, extending her head-to-head lead to 1-0.\n- Mirra Andreeva (No. 8) beats Sorana Cirstea (No. 18) 6-0, 6-3 in the quarterfinal.\n\n## Player Form\n\n- ..."
}
```

### 1.5 Validation (in the calling job, after parsing)

This is the safety net behind the prompt's grounding rules — cheap entity checks, not a full fact-checker:
- Confirm the output parses as `{ title, body_markdown }`.
- Extract candidate player and tournament names from `body_markdown` and confirm each appears in the fact sheet's set of supplied names. Flag (don't silently publish) any that don't — this catches a hallucinated entity.
- Store the result as `DRAFT` along with the exact fact sheet used (`source_data`) and model identifier, for traceability. Publishing is a separate manual step in MVP.

---

## 2. Tier 3 reconciliation classifier

> ✅ **Built in Phase 6b** (`reconcile/Tier3ReconciliationJob` + `Tier3Prompts`/`Tier3Parsing`). It runs as an offline **batch over the review queue** — scheduled (default daily 07:00 UTC, `app.reconcile.tier3-cron`) and also on-demand via `POST /api/admin/reconcile/tier3` — never on the hot poll path, using **Haiku** (`claude-haiku-4-5`) through `LlmClient`. Migration `V7` added `entity_map.tour`/`country_code`/`rank_hint` so each row's candidate set is re-derived (via the shared `CandidateFinder`); the prompt and validation below are as-built.

Reached only when Tiers 0–2 (cache, deterministic string match, rules scorer) cannot uniquely resolve an external player. The model picks the best match from a **supplied candidate set** — or "none" — with a confidence score and a one-line rationale. It is doing disambiguation over evidence, not recall.

### 2.1 Input schema (assembled by the reconciliation service)

```json
{
  "external_entity": {
    "source": "provider_x",
    "external_id": "100234",
    "name": "C. Alcaraz",
    "tour": "ATP",
    "observed_context": {
      "country": "ESP",
      "current_rank_hint": 2,
      "recent_tournament": "Roland Garros"
    }
  },
  "candidates": [
    {
      "player_id": 207989,
      "name": "Carlos Alcaraz",
      "country": "ESP",
      "birth_year": 2003,
      "current_or_last_rank": 2,
      "recent_tournaments": ["Roland Garros", "Rome", "Madrid"]
    },
    {
      "player_id": 144750,
      "name": "Carlos Alcaa",
      "country": "ARG",
      "birth_year": 1996,
      "current_or_last_rank": 412,
      "recent_tournaments": ["Buenos Aires Challenger"]
    }
  ]
}
```

Notes for the service assembling this:
- `observed_context` carries whatever distinguishing signals the live provider gave (some give country/rank, some don't). Omit fields you don't have rather than guessing.
- Keep `candidates` to the plausible few that Tier 1/2 surfaced — not the whole player table. The model's job is to choose among a short list, not search a database.
- Every candidate's attributes come from the database; the external entity's come from the provider.

### 2.2 System message

```
You are an entity-resolution assistant for a tennis data system. Your job is to
decide which known player (if any) an external data record refers to.

Rules:
- Choose AT MOST ONE candidate from the provided <candidates> list, identified by
  its player_id. You may ONLY return a player_id that appears in that list.
- If no candidate is a good match, return null. This is the correct answer when the
  external record refers to someone not in the list (e.g. a junior, qualifier, or
  lower-tour player absent from the historical set). Do not force a match.
- Base your decision ONLY on the data provided in the request. Do not use any
  outside knowledge about real players, rankings, or results. If the data does not
  distinguish the candidates, say so via a lower confidence score.
- Weigh corroborating signals: matching surname/name, country, approximate age
  (birth year), ranking proximity, and overlapping recent tournaments all increase
  confidence. A name match alone, with conflicting country or rank, is weak.

Confidence calibration (0.0 to 1.0):
- 0.9-1.0: name plus at least one other strong signal (country, rank, or
  tournament overlap) agree, and no candidate is a plausible rival.
- 0.6-0.9: name matches and signals are consistent, but evidence is thin or a
  rival candidate is not fully ruled out.
- below 0.6: weak or conflicting evidence. The calling system will route anything
  below its threshold to human review.
- For a null result, set confidence to your confidence that NO candidate matches.

Output format:
- Respond with a single JSON object and nothing else: no prose, no code fences.
- Shape: { "player_id": number | null, "confidence": number, "rationale": string }
- "rationale" is one sentence citing the specific signals that drove the decision.
```

### 2.3 User message

```
Resolve the following external record against the candidate list.

<external_entity>
{{EXTERNAL_ENTITY_JSON}}
</external_entity>

<candidates>
{{CANDIDATES_JSON}}
</candidates>

Respond with only the JSON object.
```

### 2.4 Worked examples (optional few-shot — include if accuracy needs lifting)

Including one or two of these in the system message, before the live request, measurably improves calibration on collisions. Add them only if you see the model over-matching.

Strong match:
```json
{ "player_id": 207989, "confidence": 0.97, "rationale": "Surname, Spanish nationality, rank ~2, and Roland Garros all align with one candidate; the rival is an Argentine ranked 412 with no overlap." }
```

Name collision, resolved by secondary signals:
```json
{ "player_id": 144750, "confidence": 0.64, "rationale": "Both candidates share the surname, but observed country ARG and a Challenger-level recent event match only the lower-ranked candidate." }
```

No match (external player not in the set):
```json
{ "player_id": null, "confidence": 0.88, "rationale": "No candidate shares the surname or country; the external record is likely a qualifier absent from the historical data." }
```

### 2.5 Validation (in the reconciliation service, after parsing)

- Confirm the output parses as `{ player_id, confidence, rationale }`.
- Confirm `player_id` is either `null` or a value that **was in the supplied candidate list** — reject and route to review if the model returns an id that wasn't offered (a guard against the model inventing an id).
- Apply the confidence threshold: at/above → write the confirmed mapping to `entity_map`; below → store unmapped and enqueue for human review.
- Record `confidence`, `rationale`, and the model identifier on the mapping for auditability. Every confirmed mapping (here or in earlier tiers) makes the next encounter a free Tier 0 cache hit.

---

## 3. Kotlin integration sketch

Both prompts go through the same `LlmClient` interface (design doc §6.5). **As built** (`insight/LlmClient.kt`) — the model + token budget are per-call so the digest (Sonnet) and Tier-3 (Haiku) share one client:

```kotlin
interface LlmClient {
    fun complete(system: String, user: String, model: String, maxTokens: Int): String
}

// AnthropicLlmClient: POST https://api.anthropic.com/v1/messages
//   headers: x-api-key, anthropic-version: 2023-06-01
//   body: { model, max_tokens, system: [{type:"text", text, cache_control:{type:"ephemeral"}}],
//           messages: [{role:"user", content}] }
//   response: content[0].text

// --- Digest ---
data class DigestResult(val title: String, val bodyMarkdown: String)  // @JsonProperty("body_markdown")

// --- Reconciliation (Phase 6b) ---
data class ReconClassification(val playerId: Long?, val confidence: Double, val rationale: String)
```

Implementation notes:
- Jackson 3 on Boot 4: inject `tools.jackson.databind.ObjectMapper`; `@JsonProperty` is still imported from `com.fasterxml.jackson.annotation`.
- Defensively strip a leading/trailing ```json fence before parsing (`insight/DigestParsing.kt`).
- Prompt text lives in `insight/DigestPrompts.kt` constants with `{{…}}` substitution — out of business logic so it can be tuned without code changes.
- Config in env per design doc §11: `ANTHROPIC_API_KEY` (or `LLM_API_KEY`), `LLM_MODEL` (default `claude-sonnet-4-6`), `LLM_TIER3_MODEL` (default `claude-haiku-4-5`). The prompts themselves are provider-agnostic.
```

### 3.1 Tuning later
Treat these prompts as a v1. Once you have real fact sheets and real reconciliation collisions flowing, the highest-leverage adjustments are usually: trimming the fact sheet down (smaller is better), adding one or two few-shot examples to the classifier if it over-matches, and tightening the digest length/tone instructions to taste. For deeper prompt-engineering guidance see https://docs.claude.com/en/docs/build-with-claude/prompt-engineering/overview.
