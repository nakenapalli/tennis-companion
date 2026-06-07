# Tennis Companion — MVP Design Document

> A tennis app that helps fans **learn about and enjoy** the sport, rather than a live‑betting or pure‑scores product. This document is written to be handed to a Claude Code session as the build spec. It defines scope, architecture, data model, APIs, the AI insight pipeline, and a recommended build sequence.

---

## As‑built status (updated 2026‑06‑02)

This spec has now been implemented through **Phase 6b**. The doc below has been updated to match what was built; the most significant deltas from the original spec:

- **Live provider = API Tennis (api‑tennis.com)**, not a RapidAPI provider. Auth is an `APIkey` **query param** (not a header); Starter tier ≈ 8,000 req/day (14‑day trial). The provider‑agnostic adapter made the swap cheap. See the briefer for setup.
- **Spring Boot 4.0.x** (→ Spring Framework 7, Jackson 3 `tools.jackson.*`; JSON annotations still `com.fasterxml.jackson.annotation`), JVM 21, Gradle Kotlin DSL. Frontend is **Next.js 16** (App Router) + TypeScript + SWR.
- **LLM = Anthropic Claude** via the Messages API behind `LlmClient`: **Sonnet** (`claude-sonnet-4-6`) for the digest, **Haiku** (`claude-haiku-4-5`) reserved for Tier‑3.
- **Player‑id namespacing:** ATP and WTA Sackmann ids collide, so the canonical `player_id` is **ATP = raw id, WTA = raw id + 1,000,000,000**.
- **Scheduled polling is ON by default** (the big quota made on‑demand‑only unnecessary); live poll runs at a fixed 60s interval (configurable). **Adaptive cadence and SSE are deferred.** Reads use REST + SWR polling.
- **Done:** Phases 0–5, the provider swap, **Phase 6a** (weekly AI digest backend + serving + admin publish), and **Phase 6b** — reconciliation **Tier 3** (an offline, admin-triggered LLM classifier over the review queue) and the **frontend digest page** (`/insights`). **Pending:** Phase 7 polish.

The original spec text follows, lightly amended where it would otherwise be misleading.

---

## 1. Purpose and product framing

The goal is a companion app for tennis fans that answers, on one screen: *what did I miss, what's worth watching right now and this week, and why is it interesting?* It leans on **free historical data** plus a **single cheap live‑ish feed**, and uses an LLM as an **offline content generator** (not a chatbot) to produce short, grounded editorial pieces.

The defining product bet is editorial, not technical: existing apps (e.g. TNNS) already do scores well. The wedge is the "learn and enjoy" layer — context, narrative, and curation — which is underserved.

### What this is NOT
- Not a betting or odds product. No bet suggestions, no odds ingestion, no probability‑of‑winning surfacing.
- Not a sub‑second real‑time scores product. Data is **poll‑based** (minutes, not seconds).
- Not a conversational AI assistant. AI runs as scheduled batch jobs that write articles; there is no live chat endpoint in the MVP.

---

## 2. ⚠️ Licensing — read before building

This is the single most important constraint and it shapes data‑source choices.

- The free historical datasets this design relies on (Jeff Sackmann's `tennis_atp` / `tennis_wta` repos, and the community `TML-Database`) are licensed **Creative Commons Attribution‑NonCommercial‑ShareAlike 4.0**. They are excellent for a **personal / portfolio / non‑commercial** build and require **attribution**.
- **Do not ship a commercial product on this data.** Charging money, running ads, or gating features behind a subscription likely falls outside the NonCommercial license. A commercial launch requires a properly licensed data feed instead (which reintroduces cost and is explicitly out of scope for this MVP).
- Live‑feed APIs (RapidAPI tennis providers, etc.) each carry their own Terms of Service that often restrict **how data may be stored, cached, displayed, and redistributed**, and some forbid building a competing scores product. **Read the chosen provider's ToS before building the UI around it**, and keep all provider‑specific assumptions isolated in one adapter (see §6.1).

**Build assumption for this MVP:** personal / portfolio build, non‑commercial, with visible attribution to data sources in the app footer/about screen.

---

## 3. Scope (MVP)

### In scope
1. **Customizable home screen** — a per‑user configurable dashboard of widgets: recent results you missed, currently live matches, latest ATP/WTA rankings, favorite‑player activity, and the latest AI digest. Each widget is tappable to go deeper.
2. **Poll‑based scores & rankings** — live‑ish match status and recent results, plus current ATP/WTA singles rankings, pulled from one cheap API on a server‑side schedule.
3. **Player & tournament detail** — player profiles backed by historical data (career results, head‑to‑head), and a "current tournaments" view.
4. **One AI artifact: the weekly "What's Worth Watching" digest** — a short editorial piece generated weekly, grounded in real database facts (tournaments, notable matchups, rankings) for all scores/names, and blended with **scraped full-text tennis news** (manually-chosen sites, e.g. tennis.com) for added context and a more natural voice. Articles are used transiently and never persisted; any article-sourced fact is cited inline; an anti-plagiarism check guards copying; and a post-generation LLM **fact-check** against the DB gates **auto-publish** (clean → published, else DRAFT). The published digest is embedded on the home page under the scores.

### Out of scope (future)
- Doubles, mixed, team events (Davis/BJK Cup) — singles tour‑level only for MVP.
- Push notifications.
- Additional AI artifact types (player spotlights, match retrospectives, sentiment analysis) — designed for, but not built in MVP.
- Native mobile build (see §7 for the recommended path).
- Event‑streaming / Kafka. A scheduled poller plus Redis covers MVP needs; streaming can be introduced later as a deliberate learning exercise, not a requirement.

---

## 4. Tech stack

| Layer | Choice | Notes |
|---|---|---|
| Language / framework | Kotlin + **Spring Boot 4.0.x** (JVM 21) | Spring Boot supports Kotlin as a first‑class language. `@Scheduled` covers all polling needs. (Boot 4 → Spring Framework 7, Jackson 3 in the `tools.jackson.*` packages — though `@JsonProperty` etc. are still `com.fasterxml.jackson.annotation`; split `*-test` starters.) |
| Build | Gradle (Kotlin DSL, `build.gradle.kts`) | Keeps build script and app code in the same language. Generated from `start.spring.io` (Kotlin + Gradle). |
| Relational store | PostgreSQL 16 | Historical data, reference data, users, generated content. |
| Cache / fan‑out | Redis 7 | Holds the latest poll results so client traffic never hits the upstream API. |
| Migrations | Flyway | Versioned SQL migrations under `src/main/resources/db/migration`. |
| LLM | **Anthropic Claude** (Messages API) | Called only from the scheduled insight job, never per user request, behind the `LlmClient` interface. Sonnet `claude-sonnet-4-6` for the digest; Haiku `claude-haiku-4-5` reserved for reconciliation Tier‑3. |
| Frontend | **Next.js 16** (App Router, React) + TypeScript + SWR | See §7 — web first for MVP speed; React Native is the productionization path. Plain CSS (no Tailwind). |
| Local orchestration | docker‑compose | Postgres + Redis + backend + frontend. |
| Hosting (later) | Fly.io / Railway / Render | Cheap managed Postgres + Redis + container. Not needed to build the MVP locally. |

**Cost‑control principle (the whole reason the design works):** the backend polls the upstream API **once on a schedule and fans the result out to all clients via Redis**. Upstream request volume scales with *matches being played*, not *number of users*. This keeps the app inside free / low‑cost API tiers even with real traffic.

**Kotlin guidance for the build session:** the developer is learning Kotlin, so favor idiomatic Kotlin over Java‑style code and add brief comments where an idiom (null‑safety operators, data classes, scope functions, extension functions) is doing something a Java developer wouldn't expect. Use `data class` for DTOs and domain types, model nullability honestly in the type system (`Player?` for the not‑yet‑reconciled case), prefer immutable `val`, and use Kotlin‑first libraries where they're the norm (e.g. the Jackson Kotlin module for JSON, kotlinx‑coroutines only if genuinely needed — `@Scheduled` does not require it). Don't introduce coroutines/reactive (WebFlux) for MVP; plain blocking Spring MVC is simpler to learn against and sufficient for poll‑based traffic.

---

## 5. Architecture overview

```
External Tennis API  ──poll (every few min)──►  Spring Boot Poller
                                                     │
                                        ┌────────────┴────────────┐
                                        ▼                         ▼
                                   Redis cache               PostgreSQL
                                  (live results)        (historical + generated)
                                        │                         │
                                        └────────────┬────────────┘
                                                     ▼
                                          Your REST / SSE API
                                                     │
                                                     ▼
                                            Clients (web, later mobile)

   AI insight job (scheduled, weekly):  PostgreSQL ──fact sheet──► LLM ──article──► PostgreSQL
```

Key properties:
- The **poller** is the only thing that talks to the upstream API.
- The **API layer** reads from Redis first, falling back to Postgres; it never calls upstream synchronously.
- The **AI job** reads structured facts from Postgres, calls the LLM, and writes finished articles back to Postgres. The serving API treats AI articles like any other stored content.

---

## 6. Backend components

All under a package root such as `com.tenniscompanion`.

### 6.1 Upstream data adapter (`integration/`)
A single adapter that wraps the chosen live API. **All provider‑specific knowledge lives here** (auth headers, endpoint shapes, rate limits, response DTOs). The rest of the app depends only on the adapter's normalized output types (`NormalizedMatch`, `NormalizedRanking`, `NormalizedTournament`). This isolation makes it cheap to swap providers if ToS or pricing forces a change.

- Respect upstream rate limits with a client‑side limiter.
- Map upstream errors to a small set of internal exceptions; never let an upstream outage take down serving (serve last cached/persisted data instead).

### 6.2 Pollers (`poller/`)
- **`LiveScorePoller`** — `@Scheduled`, **ON by default** (the API Tennis quota is generous, so on‑demand‑only was unnecessary). Runs at a **fixed 60s interval** (`app.poll.live-interval`, configurable). On each run: fetch live matches → normalize → reconcile each player (§6.4, via a shared `LiveMatchMapper`) → write `scores:live` to Redis → upsert `live_matches`. *Adaptive cadence (faster when live, back off when idle) is **deferred**.*
- **`RankingsPoller`** — `@Scheduled` daily. Fetches current ATP & WTA singles rankings (`get_standings`), writes `rankings:atp` / `rankings:wta` to Redis and upserts `live_rankings`.
- **`TournamentSyncJob`** — `@Scheduled` daily. Derives current tournaments from `get_fixtures` (no dedicated endpoint), **deduped by tournament name** (api‑tennis splits one event across many `tournament_key`s — ATP/WTA singles, doubles, juniors), classified by highest tier; dates derived from the matches' dates over a window. *Draws/seeds and surface are unavailable from this feed and are deferred (the `surface`/`draw` columns stay null).* Each sync replaces the source's current set.
- **`RecentScoresJob`** *(added)* — `@Scheduled` (~15 min). Pulls **today's + yesterday's completed** singles matches from `get_fixtures`, reconciles, and caches `scores:recent` (most‑recent‑first, capped) — backs the "recently completed" view + the digest's results.

Cadence values are **configuration**, not hard‑coded (see §11). A live poll only runs when a feed key is configured.

### 6.3 Historical data loader (`loader/`)
A one‑time CLI/runner (e.g. Spring Boot `ApplicationRunner` guarded by a profile/flag) that ingests the Sackmann CSVs into `players`, `rankings_history`, and `matches`. For MVP, load the most recent N seasons (e.g. last 5) to keep the dataset and build time manageable; the schema supports loading the full archive later. Must be idempotent (safe to re‑run).

### 6.4 Entity reconciliation service (`reconcile/`)
The unglamorous core problem: **Sackmann player IDs ≠ live‑API player IDs**, and names differ (initials, accents, transliteration). This service maps an external `(source, external_player_id, external_name)` to an internal `player_id`. It is structured as a **tiered cascade** so that the expensive option (an LLM) is only ever invoked on the genuinely hard residue, never on the easy bulk.

**Design principle:** most cases are trivially unambiguous and should be resolved deterministically for free. AI earns its place only on name collisions, players absent from the historical set, and transliteration ambiguity. AI proposes, a confidence threshold disposes, and anything below threshold waits for human review.

The cascade, in order:

1. **Tier 0 — cache hit (`entity_map` lookup).** Before any matching, check `entity_map` for an existing confirmed mapping for this `(source, external_player_id)`. If present, resolve instantly. This is what makes the whole system get cheaper over time (see feedback loop below).
2. **Tier 1 — deterministic string match.** Normalize the external name (case‑fold, accent‑fold, expand/normalize initials) and match against `players`, surname‑weighted and constrained by tour (ATP/WTA). A unique high‑quality match resolves here. Resolves the large majority of cases at microsecond cost.
3. **Tier 2 — rules‑based scorer.** For cases Tier 1 can't uniquely resolve, score candidates using additional structured signals already in the data: tour, current/last ranking proximity, country code, and age/birth‑year. If one candidate clears a confidence margin over the rest, auto‑map it.
4. **Tier 3 — LLM classifier (last resort).** Only the still‑ambiguous remainder reaches the model. Pass it the external entity plus the small set of candidate players and their retrieved context (recent results, ranking, country, recent tournaments), and ask it to choose the best match (or "none") **with a confidence score and a one‑line rationale**. The model is doing disambiguation/classification over evidence you supply — not recalling facts from memory — which is a safe, defensible LLM use. Reuse the `LlmClient` interface from §6.5.

**Confidence threshold + human review.** Every auto‑map (Tiers 2 and 3) records a `confidence` value. Results at or above the threshold are written as confirmed mappings; results below it are stored **unmapped** and queued for human review via the admin endpoint (§8). The threshold and the review queue are not optional — they are what prevent a wrong auto‑match from silently attaching one player's career history to a same‑surnamed qualifier.

**Feedback loop (why it trends toward free).** Every confirmed mapping — whether resolved deterministically or by the LLM — is written to `entity_map`. The next time that same external ID appears, Tier 0 resolves it instantly. The LLM's expensive one‑time judgments become cheap permanent lookups, so over a season the share of players needing Tier 3 trends toward zero. This is the "separate store of synthesized knowledge about known entities" — and it is just the relational `entity_map` table plus the distinguishing attributes already on `players`. **A vector/embedding store is not needed for MVP**; with only a few thousand pro players, the tiered relational approach is simpler and sufficient. Reserve semantic/embedding matching for a later scale problem if one ever materializes.

**Never block serving.** Live matches must render even when a player is unmapped — fall back to the external display name. Reconciliation enriches; it must never block the serving path.

### 6.5 AI insight job (`insight/`)
- **`WeeklyDigestJob`** — `@Scheduled` weekly. See §9 for the full design. In short: assemble a structured **fact sheet** from Postgres, build a grounded prompt, call the LLM through the `LlmClient` interface, store the result in `generated_insights` as `DRAFT`. Publishing can be a manual flip to `PUBLISHED` for MVP (a guard against a bad generation reaching users); auto‑publish is a later option.
- **`LlmClient`** — interface with one implementation; keeps the provider swappable and makes the job unit‑testable with a stub.

### 6.6 Serving API (`api/`)
Spring MVC controllers (see §8). Read path: Redis → Postgres fallback. No synchronous upstream calls. **As built, the client polls our own REST API via SWR** (e.g. live scores refresh every 30s) — simple and sufficient. *SSE (`text/event-stream`) push is **deferred**; the design still favors SSE over WebSockets if/when push is added (an api‑tennis WebSocket also exists but is Business‑tier and unnecessary for minute‑level freshness).*

---

## 7. Frontend

**Recommendation for MVP: Next.js (React) web app, TypeScript.**

Rationale and the assumption being made: the original interest referenced a mobile app (TNNS) and an eventual market launch, which points at mobile long‑term. But for an MVP whose purpose is to **prove the editorial concept cheaply**, a web app is dramatically faster to build for a backend‑focused developer, requires no app‑store process, and shares 100% of the backend. **React Native is the recommended productionization path** once the concept is validated — the API contract in §8 is client‑agnostic and won't change.

Screens (as built unless noted):
- **Home** — dashboard: favorites (when logged in), a live‑or‑recently‑completed scores strip, and ATP top‑5. *(Fully configurable widgets are simplified for MVP.)*
- **Scores** — one conditional section: live matches if any, otherwise today's recently‑completed (SWR polling; **no SSE**).
- **Rankings** — ATP / WTA toggle.
- **Player detail** — profile, recent results (Sackmann), head‑to‑head, add‑to‑favorites.
- **Tournaments** — current list (no draw info — feed doesn't provide it).
- **Settings** — manage favorites, plus an **"ATP & WTA only" display toggle** (default on; hides Challenger/ITF/junior events). Persisted to localStorage so it works for anonymous users too.
- **Digest** — ✅ the latest published "What's Worth Watching" digest is **embedded on the home page under the scores** (no separate tab), fetched from `insights/latest` and rendered with a small dependency-free Markdown renderer (`components/Markdown.tsx`). It only appears when a digest is published, so any failure (scrape/LLM/fact-check) gracefully falls back to scores + rankings.

State: SWR for server state; the display toggle via a small client context. No client‑side secrets — the LLM and upstream keys live only on the backend.

---

## 8. API surface

JSON over HTTPS. All times ISO‑8601 UTC. Auth via session or JWT (see §10). `{userId}` endpoints require auth; public read endpoints do not.

### Public read
- `GET /api/scores/live` → currently live matches (from `scores:live` cache).
- `GET /api/scores/recent` → recently completed matches (today + yesterday, most‑recent‑first; no params).
- `GET /api/rankings?tour=ATP|WTA&limit=100` → current ranking snapshot.
- `GET /api/players/{playerId}` → profile (bio + current rank).
- `GET /api/players/{playerId}/matches?limit=20` → recent/historical results.
- `GET /api/players/{playerId}/h2h?opponentId={id}` → head‑to‑head record from historical data.
- `GET /api/tournaments/current` → current/upcoming tournaments.
- `GET /api/tournaments/{tournamentId}` → detail + draw/seeds if available.
- `GET /api/insights/latest?type=weekly_digest` → latest published digest.
- `GET /api/insights/{insightId}` → a specific published insight.

### Authenticated (user)
- `GET /api/me/home-config` / `PUT /api/me/home-config` → widget list, order, options.
- `GET /api/me/favorites` / `POST /api/me/favorites` / `DELETE /api/me/favorites/{playerId}`.
- `GET /api/me/home` *(optional convenience)* → server‑composed payload for all configured widgets in one call, to reduce round‑trips on the home screen.

### Live updates
- `GET /api/scores/stream` (SSE) — **deferred / not built** (clients poll `/api/scores/live` via SWR instead).

### Admin (require `ROLE_ADMIN`)
- `GET /api/admin/unmapped-entities` → external players awaiting reconciliation.
- `POST /api/admin/entity-map` → confirm a mapping.
- `POST /api/admin/poll/{live|rankings|tournaments|recent}` → on‑demand poll triggers (the scheduled jobs run anyway).
- `POST /api/admin/insights/generate` → generate a digest DRAFT now.
- `GET /api/admin/insights?status=DRAFT` / `POST /api/admin/insights/{id}/publish`.

---

## 9. AI insight pipeline (the one AI feature)

### Principle: ground everything, generate nothing factual from memory
An LLM asked for "tennis tidbits" or "head‑to‑head records" will confidently invent scorelines and stats. **The model must never state a bare fact from its own memory.** It receives the real numbers and writes narrative *around* them.

### `WeeklyDigestJob` flow
1. **Assemble a fact sheet** from Postgres (`insight/FactSheetBuilder`) — a structured JSON object (see the prompts doc §1.1 for the exact as‑built shape):
   - current main‑tour (ATP/WTA) tournaments (name, level, dates),
   - top ATP/WTA players and their current rankings,
   - a few notable **completed** matchups, each pre‑resolved into unambiguous facts so the model can't misread them: a canonical `result` string ("X beats Y 6‑3, 4‑6, 6‑0", winner‑first, all sets), winner/loser + ranks, a clean `round` ("Round of 16"), a plain H2H summary ("X leads 2‑1") + last meeting,
   - recent form (last few results) for the headline players.
   Player names/ranks come from the **reconciled Sackmann profile** (not the feed's "C. Alcaraz" display strings); H2H/form come from `matches`. Everything is a real DB value.
1b. **Scrape news context** *(added)* — `NewsSource`/`ScrapedNewsSource` scrapes recent **full** articles from manually‑chosen sites (one `SiteScraper` per source, e.g. `TennisDotComScraper`): discover article URLs from the site's news index, fetch each page, extract title/author/date/body, rank by mentions of the week's players/tournaments. Articles are used **transiently only — never persisted**. The digest is a news‑enriched artifact: if no article can be scraped, the run is **skipped**.
2. **Build the prompt**: provide the fact sheet (authoritative for all scores/names) and the `<articles>` block. The model writes "what's worth watching this week and why" using fact‑sheet facts (uncited) plus article context — but must reword everything (no copying) and **cite any article‑sourced fact inline** as a markdown link, citing only supplied sources.
3. **Call the LLM** via `LlmClient`.
4. **Validate**: (a) **anti‑plagiarism** — `verbatimOverlaps` flags any long verbatim run shared with a source; on a hit, regenerate once with a stricter reminder, then abort rather than save a copy; (b) **fabricated citations** + (c) **ungrounded entities** — advisory logs.
5. **Store** the result in `generated_insights`, with `source_data` = the **fact sheet only** (our own DB data; **no article data is persisted**) + the model id.
6. **Fact-check & auto-publish**: a second LLM pass (`FactCheckPrompts`) verifies the digest's hard facts against the (whole, small) fact sheet. If it runs clean → **auto-publish**; on any contradiction or if the check couldn't run → leave `DRAFT`. The serving API + home page only show published insights.

Keep the AI's job to **framing and curation** — with all facts injected. *As built (per user feedback), the digest is a **concise, scannable results roundup**: a plain title, a few model‑chosen `##` sections ("Top Men's Results", "Notable Upsets", …) with one‑sentence `-` bullets, present tense, plain verbs. The prompt forbids editorializing the score/round (state the `result` and `round` verbatim; never narrate a set‑by‑set trajectory or infer a stage). See the prompts doc §1 for the current prompt. The original "250–400 word warm editorial" framing was tuned down to this.*

---

## 10. Auth
Email‑based auth with sessions or JWT. For MVP, a single user role plus an `admin` flag is enough (admin gates the reconciliation and insight‑publishing endpoints). Store only what's needed: `users(id, email, password_hash, is_admin, created_at)`. Use Spring Security with BCrypt. No third‑party login required for MVP.

---

## 11. Configuration & secrets
All via environment variables / Spring profiles; nothing committed. Provide a `.env.example`.

| Var | Purpose |
|---|---|
| `TENNIS_API_BASE_URL` (`https://api.api-tennis.com/tennis/`), `TENNIS_API_KEY` | Upstream live feed (API Tennis; key is the `APIkey` query param). |
| `POLL_ENABLED` (default `true`), `POLL_LIVE_INTERVAL` (default `PT1M`), `POLL_RECENT_INTERVAL` (`PT15M`) | Poll cadences. *(Adaptive active/idle intervals deferred.)* |
| `POLL_RANKINGS_CRON`, `POLL_TOURNAMENTS_CRON`, `DIGEST_CRON` | Job schedules. |
| `DATABASE_URL`, `REDIS_URL` | Stores. |
| `ANTHROPIC_API_KEY` (or `LLM_API_KEY`), `LLM_MODEL`, `LLM_TIER3_MODEL` | Insight generation. The effective key is `LLM_API_KEY ?: ANTHROPIC_API_KEY`. |
| `JWT_SECRET` / session config | Auth. |

*`.env` is loaded by a custom `DotenvEnvironmentPostProcessor` (spring‑dotenv doesn't work on Boot 4); the `.env` property source sits just above `systemEnvironment` so its values resolve reliably while command‑line args still override.*

---

## 12. Data model (Postgres)

Illustrative DDL — refine types/indexes during build. Three groups: historical/reference, live/app‑managed, and user.

```sql
-- ===== Historical & reference (loaded from Sackmann CSVs) =====
CREATE TABLE players (
  player_id        BIGINT PRIMARY KEY,       -- canonical id: ATP = raw Sackmann id; WTA = raw id + 1,000,000,000
  source_player_id BIGINT NOT NULL,          -- raw Sackmann id (ATP & WTA ids collide, hence the offset above)
  first_name       TEXT,
  last_name        TEXT,
  hand             TEXT,                     -- R/L/U (TEXT, not CHAR(1), to satisfy ddl-auto=validate)
  birth_date       DATE,
  country_code     TEXT,                     -- IOC 3-letter (GER, SUI, …) as Sackmann uses
  height_cm        INT,
  tour             TEXT NOT NULL             -- 'ATP' | 'WTA'
);
CREATE INDEX idx_players_name ON players (lower(last_name), lower(first_name));

CREATE TABLE rankings_history (
  ranking_date   DATE NOT NULL,
  player_id      BIGINT NOT NULL REFERENCES players(player_id),
  rank           INT,
  points         INT,
  tour           TEXT NOT NULL,
  PRIMARY KEY (ranking_date, player_id, tour)
);

CREATE TABLE matches (
  id             BIGSERIAL PRIMARY KEY,
  tourney_id     TEXT,
  tourney_name   TEXT,
  surface        TEXT,                       -- Hard/Clay/Grass/Carpet
  tourney_level  TEXT,
  tourney_date   DATE,
  match_num      INT,
  round          TEXT,
  best_of        INT,
  winner_id      BIGINT REFERENCES players(player_id),
  loser_id       BIGINT REFERENCES players(player_id),
  score          TEXT,                       -- e.g. "6-3 4-6 7-5"
  tour           TEXT NOT NULL
  -- optional: add stat columns (aces, df, etc.) later if needed
);
CREATE INDEX idx_matches_winner ON matches (winner_id);
CREATE INDEX idx_matches_loser  ON matches (loser_id);

-- ===== Live & app-managed (synced from upstream API) =====
CREATE TABLE tournaments (
  id             BIGSERIAL PRIMARY KEY,
  source         TEXT NOT NULL,
  external_id    TEXT NOT NULL,
  name           TEXT NOT NULL,
  level          TEXT,
  surface        TEXT,
  location       TEXT,
  tour           TEXT,
  start_date     DATE,
  end_date       DATE,
  draw           JSONB,                      -- seeds/draw if available
  UNIQUE (source, external_id)
);

CREATE TABLE live_matches (
  id             BIGSERIAL PRIMARY KEY,
  source         TEXT NOT NULL,
  external_id    TEXT NOT NULL,
  tournament_id  BIGINT REFERENCES tournaments(id),
  status         TEXT NOT NULL,              -- scheduled | live | finished
  round          TEXT,
  surface        TEXT,                       -- null (api-tennis live/fixtures don't provide surface)
  tour           TEXT,
  category       TEXT,                       -- circuit: ATP | WTA | Challenger | ITF | Junior … (V5; for main-tour filtering)
  tournament_name TEXT,
  player1_name   TEXT NOT NULL,              -- upstream display name (always present)
  player2_name   TEXT NOT NULL,
  player1_id     BIGINT REFERENCES players(player_id),  -- nullable until reconciled
  player2_id     BIGINT REFERENCES players(player_id),
  score          JSONB,                      -- structured set/game score
  start_time     TIMESTAMPTZ,
  last_polled_at TIMESTAMPTZ NOT NULL,
  UNIQUE (source, external_id)
);
CREATE INDEX idx_live_status ON live_matches (status);

CREATE TABLE live_rankings (
  tour           TEXT NOT NULL,
  rank           INT NOT NULL,
  player_id      BIGINT REFERENCES players(player_id),  -- nullable until reconciled
  external_name  TEXT NOT NULL,
  points         INT,
  captured_at    TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (tour, rank, captured_at)
);

-- Reconciliation source of truth
CREATE TABLE entity_map (
  source              TEXT NOT NULL,         -- 'api-tennis'
  external_player_id  TEXT NOT NULL,
  external_name       TEXT,
  player_id           BIGINT,                -- null = unmapped/needs review (soft ref, not FK)
  confidence          REAL,
  confirmed           BOOLEAN NOT NULL DEFAULT FALSE,
  tier                TEXT,                  -- CACHE | DETERMINISTIC | RULES | LLM | UNRESOLVED | MANUAL (auditability)
  rationale           TEXT,
  updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (source, external_player_id)
);

-- AI-generated content
CREATE TABLE generated_insights (
  id            BIGSERIAL PRIMARY KEY,
  type          TEXT NOT NULL,               -- 'weekly_digest' (extensible)
  title         TEXT NOT NULL,
  body_markdown TEXT NOT NULL,
  source_data   JSONB NOT NULL,              -- the fact sheet used (traceability)
  model         TEXT,
  status        TEXT NOT NULL DEFAULT 'DRAFT',-- DRAFT | PUBLISHED
  generated_at  TIMESTAMPTZ NOT NULL,
  published_at  TIMESTAMPTZ
);

-- ===== User =====
CREATE TABLE users (
  id            BIGSERIAL PRIMARY KEY,
  email         TEXT UNIQUE NOT NULL,
  password_hash TEXT NOT NULL,
  is_admin      BOOLEAN NOT NULL DEFAULT FALSE,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE user_home_config (
  user_id       BIGINT PRIMARY KEY REFERENCES users(id),
  layout        JSONB NOT NULL               -- ordered widget list + per-widget options
);

CREATE TABLE user_favorites (
  user_id       BIGINT NOT NULL REFERENCES users(id),
  player_id     BIGINT NOT NULL REFERENCES players(player_id),
  PRIMARY KEY (user_id, player_id)
);
```

### Redis keys
- `scores:live` — JSON of current live matches (TTL ≈ active poll interval).
- `scores:recent` — recently finished matches.
- `rankings:atp`, `rankings:wta` — current ranking snapshots.
- `tournaments:current` — current/upcoming list.

The serving API reads these first; on a miss it falls back to the corresponding Postgres table and (optionally) repopulates the cache.

---

## 13. Local development
`docker-compose.yml` brings up Postgres + Redis. Backend runs via Gradle/Maven; frontend via `npm run dev`. Sequence:
1. `docker compose up -d postgres redis`
2. Run Flyway migrations (on app start or explicitly).
3. Run the historical loader once (profile/flag‑guarded) to seed players/rankings/matches from local Sackmann CSVs.
4. Start the backend; pollers begin on schedule. For first run, allow a manual trigger endpoint or run‑on‑startup flag so you don't have to wait for the cron.
5. Start the frontend; point it at the backend base URL.

Provide a `README.md` documenting these steps and a `.env.example`.

---

## 14. Recommended build sequence (for the Claude Code session)

Build in vertical slices so something works end‑to‑end early. Each phase should compile, have basic tests, and be demoable.

- **Phase 0 — Scaffolding.** ✅ Done. Spring Boot project, package structure, `docker-compose` (Postgres + Redis), Flyway, health endpoint, `.env.example`, README.
- **Phase 1 — Historical foundation.** ✅ Done. Schema migrations for historical/reference tables. Sackmann CSV loader (~5 seasons, idempotent; the WTA id offset lives here). Endpoints: `players/{id}`, `players/{id}/matches`, `players/{id}/h2h`.
- **Phase 2 — Live scores & rankings.** ✅ Done (fixed 60s live cadence, not adaptive; **no SSE**). Upstream adapter (isolated) + `LiveScorePoller` + `RankingsPoller`. Reconciliation Tiers 0–2 + `entity_map` + confidence threshold + review queue. Redis cache + fallback. Endpoints: `scores/live`, `scores/recent`, `rankings`. (Tiers 0–2 run inline; their misses go to the review queue, which the Phase 6b offline Tier-3 pass then classifies.)
- **Phase 3 — Tournaments.** ✅ Done. `TournamentSyncJob` (derived from fixtures, deduped by name), `tournaments/current`, `tournaments/{id}`. Draws/seeds/surface deferred (feed doesn't provide them).
- **Phase 4 — Users & personalization.** ✅ Done. Auth (Spring Security + BCrypt, JWT/HMAC). `users`, `user_home_config`, `user_favorites`. Config + favorites endpoints.
- **Phase 5 — Frontend.** ✅ Done. Next.js 16 app: Home, Scores (live‑or‑recently‑completed, SWR polling — no SSE), Rankings (ATP/WTA), Player detail, Tournaments, Settings (incl. an **ATP/WTA‑only display toggle**). Footer attribution.
- **Provider swap (added milestone).** ✅ Done. RapidAPI "TennisApi" → **API Tennis**; new adapter/DTOs, country→IOC helper, scheduled polling on, `RecentScoresJob`, the `category` column + main‑tour filtering.
- **Phase 6a — AI weekly digest (backend).** ✅ Done. `LlmClient` + `AnthropicLlmClient`, `FactSheetBuilder`, `WeeklyDigestJob` (grounded prompt → validate → `DRAFT`), `DigestStore` + `generated_insights`, serving `insights/latest`/`{id}` + admin generate/list/publish.
- **Phase 6b — AI Tier 3 + digest frontend.** ✅ Done. Reconciliation **Tier 3** (`Tier3ReconciliationJob`) as an offline batch over the review queue (Haiku via `LlmClient`), run on a schedule (default daily 07:00 UTC, `app.reconcile.tier3-cron`) and on-demand via the admin endpoint: `V7` adds `entity_map.tour`/`country_code`/`rank_hint` so the job re‑derives candidates with the shared `CandidateFinder`, validates the chosen id was actually offered, and writes confirmed/review/no‑match back to `entity_map`. Trigger: `POST /api/admin/reconcile/tier3`. Plus the frontend **digest page** (`/insights`) rendering the published markdown.
- **Phase 7 — Polish.** ⏳ Pending. Admin reconciliation review UI, error handling for upstream outages (serve last good data), rate limiting on the upstream client, optional SSE/adaptive cadence/draws.

---

## 15. Key risks to keep front of mind during the build
1. **Licensing (recurring):** non‑commercial only on the chosen data; attribution required; do not design any paywall/ads into the MVP.
2. **Entity reconciliation is real work:** budget for it; resolve the easy bulk deterministically and reserve the LLM for the ambiguous residue; enforce the confidence threshold + human‑review queue so a wrong auto‑match can't attach the wrong career history; never let an unmapped player block rendering (fall back to upstream display name).
3. **AI grounding:** facts come from the database into the prompt; the model writes narrative around them; validate entities in the output. No facts from model memory.
4. **Upstream ToS & resilience:** keep all provider specifics in the adapter; serve cached/persisted data when upstream is down; respect rate limits.
5. **Scope discipline:** resist adding more AI artifact types, doubles, push, or streaming until the single digest + core screens are genuinely good.

---

## 16. Attribution (must appear in‑app)
Historical data derived from Jeff Sackmann / Tennis Abstract datasets, licensed CC BY‑NC‑SA 4.0. Live data courtesy of **API Tennis (api‑tennis.com)**. As built, this appears in the app **footer on every page**.
