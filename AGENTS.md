# Tennis Companion — agent guide

A **learn-and-enjoy** tennis companion app — *not* betting, *not* real-time scores, *not* a chatbot.
Kotlin/Spring Boot backend + Next.js frontend. **Poll-based**: the server polls one cheap tennis API and
fans out via Redis; free Jeff Sackmann historical CSVs back player/H2H/tournament detail; one offline AI
feature (a weekly, fully-grounded "What's Worth Watching" digest). The genuinely hard engineering is
**entity reconciliation** — mapping upstream player ids ↔ canonical Sackmann ids via a tiered cascade.

## Canonical spec — read these first
The three design docs in **`docs/`** are the source of truth (kept updated to match what's built):
- `docs/tennis-app-design-doc.md` — scope, architecture, data model, API surface, build sequence.
- `docs/tennis-app-llm-prompts.md` — the digest + Tier-3 reconciliation prompts.
- `docs/tennis-app-data-setup-briefer.md` — live API (API Tennis) + Sackmann data setup.

**Status:** Phases 0–6 are done — incl. the provider swap, **Phase 6a** (AI digest backend, verified
live) and **Phase 6b** (reconciliation **Tier 3** LLM classifier + the frontend digest page at
`/insights`). Next is **Phase 7** polish.

## Stack
- **Backend:** Kotlin + **Spring Boot 4.0** (JVM 21), Postgres 16, Redis 7, Flyway. Plain blocking Spring
  MVC (no WebFlux/coroutines). Package root `com.tenniscompanion`
  (`config/ integration/ poller/ loader/ reconcile/ insight/ api/ domain/ security/`).
- **Frontend:** **Next.js 16** (App Router) + TypeScript + SWR, plain CSS. In `frontend/` (own `AGENTS.md`).
- **Live provider:** API Tennis (api-tennis.com). **LLM:** Anthropic Claude — Sonnet for the digest, Haiku
  reserved for reconciliation Tier-3.

## Build & run (Windows / PowerShell)
The JDK is a **portable** Temurin 21, and `JAVA_HOME` is **not set globally** — set it inline before every
gradle call (a global `setx JAVA_HOME` does *not* propagate to spawned shells):
```powershell
$env:JAVA_HOME = "C:\Users\naken\tools\jdk-21.0.11+10"   # this machine's JDK 21
.\gradlew.bat build      # compile + tests; the integration tests use Testcontainers → Docker must be running
.\gradlew.bat bootRun    # backend on :8080
```
- Postgres + Redis run in Docker (containers `tc-postgres` / `tc-redis`, or `docker compose up -d postgres redis`); DB/user/pass all `tennis`.
- One-time Sackmann historical load (idempotent): `.\gradlew.bat bootRun --args='--app.historical-load.enabled=true'`.
- **Frontend:** `cd frontend; npm install; npm run dev` (:3000). `npm run build` to type-check.
- **API testing:** import `postman/tennis-companion.postman_collection.json` (set `baseUrl`; log in as admin for `/api/admin/**`).

## Environment
Copy `.env.example` → `.env` (gitignored; loaded by a custom `DotenvEnvironmentPostProcessor` whose source
sits just above OS env vars so values resolve reliably, below command-line args). Key vars:
`TENNIS_API_KEY` (the api-tennis `APIkey`) + `TENNIS_API_BASE_URL=https://api.api-tennis.com/tennis/`;
`ANTHROPIC_API_KEY` (digest LLM); `ADMIN_EMAIL`/`ADMIN_PASSWORD` (bootstraps the admin for `/api/admin/**`).

## Conventions & gotchas
- **Idiomatic Kotlin** — data classes, honest nullability (`Long?` for not-yet-reconciled), `val`; add a
  brief comment where an idiom would surprise a Java dev.
- **Jackson 3 on Boot 4:** databind is `tools.jackson.databind.*`, but `@JsonProperty` is still imported
  from `com.fasterxml.jackson.annotation`.
- **Player-id namespacing:** ATP = raw Sackmann id; **WTA = raw id + 1,000,000,000** (`player_id >= 1e9` ⇒ WTA).
- **Provider isolation:** all upstream specifics live behind `integration/TennisApiAdapter`; everything else
  depends only on the `Normalized*` types — that's what made the RapidAPI→API Tennis swap cheap.
- **Served order = importance weight:** match + tournament lists are sorted by a computed weight
  (`integration/MatchWeighting` + `TournamentTierRegistry`), so a Grand Slam final leads over a
  later-started 250 match. The feed has **no slam marker or 250/500 size**, so tier comes from a curated
  `resources/tournament-tiers.json` (matched by name, accent/case-insensitive) with the feed `category` as
  fallback; juniors are classified first. Sorting is applied on read in `LiveDataStore`/`TournamentStore`.
- **Match view + live chat:** a score card opens `/matches/{externalId}` (detail header — flags, rank, serve,
  approx elapsed/duration). Below it a **cache-only** chat (`chat/ChatStore`: Redis hashes/lists/zsets, 1-day
  TTL, **never** persisted to Postgres): threads + messages, real-time over **SSE** (`chat/ChatEventHub` —
  public read streams since `EventSource` can't send auth headers; POSTs are authenticated). Threads **lock**
  when the match is finished. Chat authors are **usernames** (`users.username`, set at registration). This is
  the one place the app uses SSE (everything else is REST + SWR polling).
- **Reconciliation never blocks serving:** unmapped players fall back to the upstream display name; the hard
  residue goes to a human-review queue. The offline **Tier-3** LLM pass (`Tier3ReconciliationJob`) classifies
  that queue against a rebuilt candidate set — scheduled (default daily 07:00 UTC, `app.reconcile.tier3-cron`)
  and also on-demand via `POST /api/admin/reconcile/tier3`.
- **The LLM is grounded only:** it writes narrative around a DB-built fact sheet (the authoritative spine
  for scores/names), never from its own memory. It blends in **scraped full-text tennis news**
  (`NewsSource`/`ScrapedNewsSource` + a per-site `SiteScraper`, e.g. `TennisDotComScraper`) for cited
  context + voice — articles are used **transiently and never persisted**, the sources used are listed in a
  trailing **Sources** line (not inline), and an **anti-plagiarism** check (`DigestParsing.verbatimOverlaps`) blocks copied
  phrasing. After generation an LLM **fact-check** (`FactCheckPrompts`) verifies the hard facts against the
  fact sheet; the job **auto-publishes only if the fact-check is clean**, else leaves a `DRAFT`. The
  published digest is **embedded on the home page** (no separate tab); if nothing is published the home
  just shows scores + rankings.
- **Licensing:** Sackmann data is CC BY-NC-SA → **non-commercial / portfolio only**, with in-app
  attribution and **no paywall/ads**.

## Verifying changes
- `.\gradlew.bat build` runs the unit + Testcontainers integration tests.
- For live checks, run the backend on **:8081** (`$env:SERVER_PORT = "8081"`) so you don't clobber a running
  :8080 instance, then exercise the admin flow (log in as admin → `POST /api/admin/poll/*`,
  `POST /api/admin/insights/generate`) and read the public endpoints / inspect the DB.
