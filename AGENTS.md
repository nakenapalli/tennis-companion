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
`/insights`). The UUID + unified-table migration is also done (see Conventions below). **Phase 7 is in
progress:** the admin reconciliation review UI, upstream-outage resilience, and upstream rate limiting
are done (see Conventions); the enrichment LLM pass and optional SSE/adaptive cadence/draws remain.

## Stack
- **Backend:** Kotlin + **Spring Boot 4.0** (JVM 21), Postgres 16, Redis 7, Flyway. Plain blocking Spring
  MVC (no WebFlux/coroutines). Package root `com.tenniscompanion`
  (`config/ integration/ poller/ loader/ reconcile/ insight/ api/ domain/ security/ enrichment/`).
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
  - **After a fresh load, dedupe players:** the Sackmann CSVs list some players under multiple ids (~226 same
    name+tour+birth-date duplicates → duplicate review candidates / split history). The load re-introduces
    them every time, so run the one-time cleanup afterward (idempotent — a no-op on an already-clean DB):
    `docker exec -i tc-postgres psql -U tennis -d tennis < scripts/dedupe-players.sql`. It's not a Flyway
    migration nor a loader guard by choice (the source is no longer public); re-run it manually after each
    reload. (A read-time net in `AdminController.candidates` also collapses same name+birth-year dupes in the
    review UI for any residue.)
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
- **Player IDs are UUIDs:** `players.id UUID` is the canonical PK everywhere (DTOs, FK columns, API responses). `sackmann_id BIGINT` is kept alongside it for traceability and Tier-3 LLM prompts (the LLM receives integer ids; after it picks one, a `SELECT id FROM players WHERE sackmann_id = ?` lookup converts back to UUID). **WTA sackmann_ids still carry the 1,000,000,000 offset** so the `UNIQUE(sackmann_id)` constraint is satisfied (raw ATP and WTA ids can overlap); `source_player_id` holds the raw pre-offset value.
- **Unified tables — no more live/historical split:** `matches` is the single table for all rows regardless of source (`source='sackmann'` for historical CSV rows, `source='api-tennis'` for live-polled rows). `rankings` replaces the old `rankings_history` + `live_rankings` pair. There are no `live_matches` or `live_rankings` tables. The `status` column (`scheduled|live|finished`) tracks a match's lifecycle; `external_id` is the API Tennis deduplication key. Both sources coexist and queries are source-agnostic unless filtering is intentional.
- **`rankings` has a surrogate PK + two source-scoped partial unique indexes (a key gotcha):** Sackmann rankings **tie** (several players share a rank on the same date when points are equal), so `rank` is *not* unique for historical rows — `uq_rankings_sackmann` enforces one row per `(source, ranking_date, tour, player_id)`. The live feed has unique ranks but possibly-null `player_id`, so `uq_rankings_live` enforces `(source, ranking_date, tour, rank) WHERE source <> 'sackmann'`. Upserts **must** name the matching partial index in their `ON CONFLICT … WHERE` (live upsert in `LiveDataStore.saveRankings`, historical insert in `HistoricalDataLoader.loadRankings`) — a plain `ON CONFLICT (source, ranking_date, tour, rank)` will fail on tied Sackmann data.
- **Provider isolation:** all upstream specifics live behind `integration/TennisApiAdapter`; everything else
  depends only on the `Normalized*` types — that's what made the RapidAPI→API Tennis swap cheap.
- **Served order = importance weight:** match + tournament lists are sorted by a computed weight
  (`integration/MatchWeighting` + `TournamentTierRegistry`), so a Grand Slam final leads over a
  later-started 250 match. The feed has **no slam marker or 250/500 size**, so tier comes from a curated
  `resources/tournament-tiers.json` (matched by name, accent/case-insensitive) with the feed `category` as
  fallback; juniors are classified first. Surface is resolved the same way via `TournamentSurfaceRegistry` +
  `resources/tournament-surfaces.json`. Sorting is applied on read in `LiveDataStore`/`TournamentStore`.
- **Enrichment queue:** the API Tennis feed rarely supplies `surface`. After each tournament sync, any
  tournament with `surface IS NULL` is queued in the `enrichment_queue` table. `EnrichmentJob` (default
  00:20 UTC, or `POST /api/admin/enrichment/run`) processes tasks via `DeterministicEnricher`, which does a
  registry lookup and — if found — writes the surface to `tournaments` and back-fills it onto API Tennis
  `matches` rows for that tournament. Tasks exhaust after 3 failed attempts; Phase 3 will add an LLM agent
  pass between deterministic failure and exhaustion. `TournamentStore.upsert()` uses
  `COALESCE(EXCLUDED.surface, tournaments.surface)` so re-syncs never clobber an enriched value.
- **Match view + live chat:** a score card opens `/matches/{externalId}` (detail header — flags, rank, serve,
  approx elapsed/duration), then a **condensed one-line Overview** (`MatchOverview`), then all sections
  **stacked one after another** (no tabs): **Momentum → Stats → Players → Head-to-head**, and (live only)
  **Discussion** at the bottom. Per-player **colors are assigned once at the page level** (`lib/playerColors.ts`,
  flag-hue-derived with per-load jitter, kept bright for the dark theme, two hues forced apart) and passed to
  every section so they stay consistent and re-roll on reload. The
  **cache-only** chat (`chat/ChatStore`: Redis hashes/lists/zsets, 1-day TTL, **never** persisted to Postgres)
  is the Discussion tab: threads + messages, real-time over **SSE** (`chat/ChatEventHub` — public read
  streams since `EventSource` can't send auth headers; POSTs are authenticated). Threads **lock** when the
  match is finished; chat authors are **usernames** (`users.username`). SSE is used only here (everything
  else is REST + SWR polling).
- **Momentum + Stats tabs (`match/` package):** both come from ONE upstream call — `get_fixtures&match_key=…`
  returns the (undocumented) `statistics` array AND `pointbypoint`, so `MatchDetailService` fetches once,
  caches the `NormalizedMatchDetail` in Redis (30s live / 12h finished / 5-min empty-sentinel for stats-less
  lower-circuit matches), and serves `GET /api/matches/{id}/momentum` + `/stats` (public, 404 when no data).
  **Momentum** is a *bespoke* metric (`MomentumCalculator`, not a standard stat): a signed, tanh-saturated
  line blending point micro-impulses, a per-game impulse weighted by game intensity (deuces/pressure points)
  × set-progress × match-progress (best-of-aware) × a streak multiplier (velocity), plus a break-of-serve
  shock and per-point decay. The adapter reconstructs each point's winner (the feed omits each game's
  *deciding* point — credit `serve_winner`; validated to reproduce a real match's 75/65/140 split exactly).
  The frontend (`components/MatchMomentum.tsx`, Chart.js) graphs it with set brackets + break markers and a
  hover tooltip showing the running score; **Stats** (`MatchStats.tsx`) is a per-period serve/return/points/
  games comparison. Stats availability varies by tour (rich on ATP/WTA, often absent on ITF/Challenger).
- **Overview / Head-to-head / Players tabs:** `MatchDetailService` also serves `GET /api/matches/{id}/h2h`
  and `/players`. **H2H** uses our Sackmann history (`PlayerService.headToHead`) when both players are
  reconciled (richer, free), else the live feed's `get_H2H` by player key. **Players** combines the DB
  profile (hand/height/age/rank) with live `get_players` career splits (titles, W-L, hard/clay/grass) and the
  player logo. The upstream **player keys** needed for those live lookups come from the same
  `get_fixtures&match_key` fixture (kept on `NormalizedMatchDetail`), so no schema change was needed. Both
  responses are Redis-cached (status-based TTL); **Overview** is frontend-only (match facts from the existing
  detail + a one-line H2H summary). Note the `matchdetail` cache key is **versioned** (`matchdetail:v5:…`) —
  bump it whenever `NormalizedMatchDetail`'s shape OR the way it's parsed changes, so stale Redis entries are ignored.
- **Reconciliation never blocks serving:** unmapped players fall back to the upstream display name; the hard
  residue goes to a human-review queue. The offline **Tier-3** LLM pass (`Tier3ReconciliationJob`) classifies
  that queue against a rebuilt candidate set — scheduled (default daily 07:00 UTC, `app.reconcile.tier3-cron`)
  and also on-demand via `POST /api/admin/reconcile/tier3`. A human reviewer works the residue on the
  admin-only **`/admin`** page (nav link shown only when `admin`): it lists the unmapped queue and, per row,
  pulls candidate canonical players from `GET /api/admin/unmapped-entities/candidates` (same
  `CandidateFinder.bySurname` set the cascade uses, rebuilt from the row's stored tour+name) and confirms a
  pick via `POST /api/admin/entity-map` — which becomes a free Tier-0 cache hit next time. To disambiguate
  namesakes the card shows the upstream player's stored **country + rank hint** (from `entity_map`) and, on
  expand, their **recent results fetched live by player key** (`GET /api/admin/unmapped-entities/upstream-matches`
  → `TennisApiAdapter.fetchPlayerMatches`, one upstream call, `get_fixtures&player_key=…` over a 180-day window).
- **Upstream resilience (serve last-good):** the read path never touches the upstream — it serves Redis-first,
  Postgres-fallback — so an outage degrades to stale-but-served. The *write* path is what's guarded:
  `ApiTennisAdapter.resultOf` throws `UpstreamApiException` on an error envelope (`success != 1`) or missing
  body so an error is never mistaken for a legit empty result (which would wipe live/recent rows); each
  poller's `@Scheduled` wraps its `poll()/sync()` in `runCatching` (logs + keeps last-good), while the
  on-demand admin trigger still propagates failures; and stores skip empty writes (`saveRankings` early-returns
  on empty, `TournamentStore.upsert` already did) so a failed refresh can't clobber the cache.
- **Upstream rate limiting:** every call through the tennis `RestClient` passes `UpstreamRateLimiter` (a
  thread-safe token bucket, applied as a request interceptor in `RestClientConfig`). It's a *safety cap* on
  the ~8,000/day quota, not a tight throttle — config via `app.tennis-api.rate-limit-per-minute` /
  `-burst` / `-max-wait-seconds`; `acquire()` blocks briefly for a permit and throws `UpstreamApiException`
  past the max wait (so a saturated limiter degrades to "skip this poll, keep last-good", caught by the poller).
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
- Run a single test class or method via Gradle's `--tests` filter (Docker must be up for integration tests):
  `.\gradlew.bat test --tests "com.tenniscompanion.insight.DigestParsingTest"` (whole class) or
  `... --tests "com.tenniscompanion.insight.DigestParsingTest.someMethod"` (one method). Wildcards work:
  `--tests "*Reconciliation*"`.
- For live checks, run the backend on **:8081** (`$env:SERVER_PORT = "8081"`) so you don't clobber a running
  :8080 instance, then exercise the admin flow (log in as admin → `POST /api/admin/poll/*`,
  `POST /api/admin/insights/generate`) and read the public endpoints / inspect the DB.
