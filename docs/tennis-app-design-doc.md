# Tennis Companion — MVP Design Document

> A tennis app that helps fans **learn about and enjoy** the sport, rather than a live‑betting or pure‑scores product. This document is written to be handed to a Claude Code session as the build spec. It defines scope, architecture, data model, APIs, the AI insight pipeline, and a recommended build sequence.

---

## As‑built status (updated 2026‑06‑24)

This spec has been implemented through **Phase 6b** and most of **Phase 7**. The doc below is kept in sync with what was built; the most significant deltas from the original spec:

- **Live provider = API Tennis (api‑tennis.com)**, not a RapidAPI provider. Auth is an `APIkey` **query param** (not a header); Starter tier ≈ 8,000 req/day (14‑day trial). The provider‑agnostic adapter made the swap cheap. See the briefer for setup.
- **Spring Boot 4.0.x** (→ Spring Framework 7, Jackson 3 `tools.jackson.*`; JSON annotations still `com.fasterxml.jackson.annotation`), JVM 21, Gradle Kotlin DSL. Frontend is **Next.js 16** (App Router) + TypeScript + SWR + Chart.js (momentum chart).
- **LLM = Anthropic Claude** via the Messages API behind `LlmClient`: **Sonnet** (`claude-sonnet-4-6`) for the digest, **Haiku** (`claude-haiku-4-5`) reserved for Tier‑3.
- **Unified, UUID‑keyed schema (the biggest delta — `V10`).** The old live/historical split is gone: a single **`matches`** table and a single **`rankings`** table hold both Sackmann history (`source='sackmann'`) and live API‑Tennis rows (`source='api-tennis'`), upserted on top of each other into a running history. There are no `live_matches` / `live_rankings` / `rankings_history` tables anymore. **`players.id` is now a UUID** (the canonical PK everywhere); the namespaced Sackmann integer survives as `players.sackmann_id` (**WTA still carries the +1,000,000,000 offset** there). See §12.
- **Match detail view + momentum (added).** A score card opens `/matches/{externalId}` with stacked sections — **Overview → Momentum → Stats → Players → Head‑to‑head**, and (live) **Discussion**. Momentum is a bespoke signed line (`match/MomentumCalculator`) built from a reconstructed point‑by‑point flow, with **match‑state leverage** (how much a set swings the match‑win probability), a discrete **set‑won impulse**, a stamina/fresh‑set regression, and a **tiebreak mini‑set** model. Both momentum + per‑period stats come from one `get_fixtures&match_key` call, Redis‑cached (`matchdetail:v5:*`).
- **Per‑match live chat over SSE (added).** A **cache‑only** chat (`chat/ChatStore`, Redis hashes/lists/zsets, 1‑day TTL, never persisted) streams threads + messages over **SSE** (`chat/ChatEventHub`); reads are public (EventSource can't send auth), POSTs are authenticated, threads lock when a match finishes. SSE is used **only** here; everything else is REST + SWR polling.
- **Tournament surface enrichment (added).** The fixtures feed carries no surface, so `TournamentSyncJob` resolves it via a `SurfaceResolver` (curated `tournament-surfaces.json` → upstream `get_tournaments` catalog) and queues the residue in `enrichment_queue`; `EnrichmentJob` retries it deterministically.
- **Scheduled polling is ON by default** (the big quota made on‑demand‑only unnecessary); live poll runs at a fixed 60s interval (configurable). A **startup data sync** (`StartupDataSync`) also pulls rankings + tournaments once on boot. **Adaptive cadence is deferred.**
- **Done:** Phases 0–6b (provider swap, weekly AI digest with fact‑check auto‑publish, Tier‑3 reconciliation, digest on the home page), the unified‑schema/UUID migration, the match‑detail/momentum view, live chat, and tournament surface enrichment. **Pending:** the enrichment LLM pass (between deterministic failure and exhaustion) and optional adaptive cadence / draws.

The original spec text follows, amended where it would otherwise be misleading.

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
A single adapter (`TennisApiAdapter` interface, `ApiTennisAdapter` impl) that wraps the chosen live API. **All provider‑specific knowledge lives here** (auth, endpoint shapes, rate limits, response DTOs). The rest of the app depends only on the adapter's normalized output types — `NormalizedMatch`, `NormalizedRanking`, `NormalizedTournament`, plus the later‑added `NormalizedMatchDetail` (point‑by‑point games + per‑period stats), `NormalizedH2HMatch`, `NormalizedPlayerCareer`, `UpstreamPlayerProfile`, and `NormalizedTournamentCatalogEntry` (surface catalog). This isolation made the RapidAPI→API‑Tennis swap cheap and keeps the match‑detail/momentum/H2H features provider‑agnostic.

- **`UpstreamRateLimiter`** — a thread‑safe token‑bucket applied as a `RestClient` interceptor (`RestClientConfig`): a *safety cap* on the ~8k/day quota, not a tight throttle (`app.tennis-api.rate-limit-per-minute` / `-burst` / `-max-wait-seconds`). `acquire()` blocks briefly for a permit, then throws `UpstreamApiException` past the max wait — so a saturated limiter degrades to "skip this poll, keep last‑good".
- **`UpstreamApiException`** — `ApiTennisAdapter.resultOf` throws it on an error envelope (`success != 1`) or missing body, so an upstream error is never mistaken for a legitimately empty result (which would wipe live/recent rows). Pollers catch it and keep the last‑good snapshot; the read path never touches upstream at all.

### 6.2 Pollers (`poller/`)
The **unified‑schema** note (see §12): pollers upsert API‑Tennis rows directly into the same `matches` / `rankings` tables that hold Sackmann history, distinguished by a `source` column — there are no separate live tables. Reads go through `LiveDataStore` / `TournamentStore` (Redis‑first, Postgres‑fallback; tier/rank/country stamped on read).

- **`LiveScorePoller`** — `@Scheduled`, **ON by default** (the API Tennis quota is generous). Runs at a **fixed 60s interval** (`app.poll.live-interval`, configurable). On each run: fetch live matches → normalize → reconcile each player (§6.4, via a shared `LiveMatchMapper`) → write the `scores:live` Redis cache → upsert `matches` (`status='live'`, `source='api-tennis'`). *Adaptive cadence (faster when live, back off when idle) is **deferred**.*
- **`RankingsPoller`** — `@Scheduled` daily. Fetches current ATP & WTA singles rankings (`get_standings`), reconciles, writes `rankings:atp` / `rankings:wta` to Redis and upserts `rankings` (`source='api-tennis'`).
- **`TournamentSyncJob`** — `@Scheduled` daily. Derives current tournaments from `get_fixtures` (no dedicated endpoint), **deduped by tournament name** (api‑tennis splits one event across many `tournament_key`s — ATP/WTA singles, doubles, juniors), classified by highest tier; dates derived from the matches' dates over a window. **Surface** (absent from the feed) is filled at write time via `SurfaceResolver`; whatever it can't resolve is queued in `enrichment_queue` (§6.7). *Draws/seeds remain deferred.* Each sync replaces the source's current set.
- **`RecentScoresJob`** *(added)* — `@Scheduled` (~15 min). Pulls **today's + yesterday's completed** singles matches from `get_fixtures`, reconciles, and caches `scores:recent` (most‑recent‑first, capped) — backs the "recently completed" view + the digest's results.
- **`StartupDataSync`** *(added)* — an `ApplicationRunner` that pulls rankings + tournaments **once on boot** (gated by `app.poll.enabled` + `app.poll.startup-sync`, both default true), so a freshly‑started instance serves current data without waiting for the next cron. Each call is `runCatching`‑wrapped, so an upstream outage just logs and keeps last‑good.

Cadence values are **configuration**, not hard‑coded (see §11). A poll only runs when a feed key is configured; each `@Scheduled` wraps its `poll()/sync()` in `runCatching` so an upstream error keeps the last‑good cache rather than crashing the job.

### 6.3 Historical data loader (`loader/`)
A one‑time CLI/runner (`HistoricalDataLoader`, a Spring Boot `ApplicationRunner` guarded by `app.historical-load.enabled`) that ingests the Sackmann CSVs into `players`, `rankings`, and `matches`. For MVP it loads the most recent ~5 seasons (2021–2026) to keep the dataset and build time manageable; the schema supports the full archive later. Idempotent (every insert uses `ON CONFLICT … DO NOTHING`). It applies the **WTA `sackmann_id` +1,000,000,000 offset** (ATP and WTA raw ids collide). Paths come from `SACKMANN_ATP_DIR` / `SACKMANN_WTA_DIR` (see the briefer). **After a fresh load, run `scripts/dedupe-players.sql`** once — the CSVs list ~226 players under multiple ids and the load re‑introduces them each time.

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
Spring MVC controllers (see §8). Read path: Redis → Postgres fallback. No synchronous upstream calls (the match‑detail tabs are the one exception: a single cached `get_fixtures&match_key` fetch on first view, §6.8). **Most reads are the client polling our own REST API via SWR** (e.g. live scores every 30s) — simple and sufficient. **SSE (`text/event-stream`) is now used for one thing: the per‑match live chat** (§6.9); scores stay poll‑based. (An api‑tennis WebSocket exists but is Business‑tier and unnecessary for minute‑level freshness.)

### 6.7 Enrichment queue (`enrichment/`)
The fixtures feed rarely supplies `surface`. After each tournament sync, any tournament still missing one is queued in `enrichment_queue`. **`EnrichmentJob`** (default 00:20 UTC, or `POST /api/admin/enrichment/run`) dequeues tasks and runs **`DeterministicEnricher`**, which resolves surface via **`SurfaceResolver`** — curated `resources/tournament-surfaces.json` (`TournamentSurfaceRegistry`, name‑matched) first, then the upstream **`UpstreamSurfaceCatalog`** (the `get_tournaments` reference list, matched by `tournament_key` = `external_id`, Redis‑cached) — and writes the surface to `tournaments`, back‑filling it onto that tournament's `matches`. Tasks exhaust after 3 failed attempts; a future LLM pass will slot between deterministic failure and exhaustion.

### 6.8 Match detail + momentum (`match/`)
**`MatchDetailService`** backs the match view's Momentum / Stats / Head‑to‑head / Players tabs. One upstream call per match — `get_fixtures&match_key=…` returns both the point‑by‑point flow and the (undocumented) statistics array — is normalized to `NormalizedMatchDetail` and Redis‑cached (`matchdetail:v5:*`, 30s live / 12h finished / 5‑min empty sentinel; the key is **versioned**, bumped whenever parsing changes). It then computes on read: **`/momentum`** (the bespoke line), **`/stats`** (per‑period serve/return/points/games), **`/h2h`** (our Sackmann history when both players are reconciled, else the feed's `get_H2H`), and **`/players`** (DB profile + live `get_players` career splits).

**`MomentumCalculator`** is a *bespoke* metric, not a standard stat — a signed, tanh‑saturated line that reads like the feel of the match. It blends: per‑point micro‑impulses (larger on break/set/match points); a per‑game impulse weighted by game **intensity** (deuces/pressure), **set progress**, and a **streak** multiplier; **match‑state leverage** — every impulse scales by how much the current set swings the match‑win probability (a 2nd‑set tiebreak matters more in best‑of‑3 than best‑of‑5; a decider most), with the remaining‑set odds a bounded blend of a rank‑based skill prior and current momentum; a discrete **set‑won impulse** at each set boundary sized by the actual ΔP in match‑win probability (so winning a tight tiebreak set counts by its stakes, not its margin); a **stamina/fresh‑set regression** so early‑set dominance fades; a **tiebreak mini‑set** where each point is a small game with its own streaks and "mini‑breaks"; a break‑of‑serve shock; and passive per‑point decay. The adapter reconstructs each point's winner (the feed omits each game's deciding point — credit `serve_winner`) and folds the separate `"Set N TieBreak"` point list back into the set's deciding game.

### 6.9 Live chat (`chat/`)
A **cache‑only**, match‑scoped chat — **never persisted to Postgres**. **`ChatStore`** keeps threads + messages + active‑chatter rankings in Redis (hashes / lists / sorted sets, 1‑day TTL). **`ChatEventHub`** fans messages out in real time over **SSE** (`SseEmitter`, 20s heartbeat) — the read streams are **public** (an `EventSource` can't send an auth header), while the POSTs that create threads/messages are authenticated and stamped with the user's `username`. Threads **lock** when the match is finished. This is the only SSE in the app.

---

## 7. Frontend

**Recommendation for MVP: Next.js (React) web app, TypeScript.**

Rationale and the assumption being made: the original interest referenced a mobile app (TNNS) and an eventual market launch, which points at mobile long‑term. But for an MVP whose purpose is to **prove the editorial concept cheaply**, a web app is dramatically faster to build for a backend‑focused developer, requires no app‑store process, and shares 100% of the backend. **React Native is the recommended productionization path** once the concept is validated — the API contract in §8 is client‑agnostic and won't change.

Screens (as built unless noted), App‑Router pages under `frontend/app/`:
- **Home (`/`)** — dashboard: favorites (when logged in), a live‑or‑recently‑completed scores strip, ATP top‑5, and the embedded digest (below).
- **Scores (`/scores`)** — live matches if any, otherwise today's recently‑completed (SWR polling).
- **Rankings (`/rankings`)** — ATP / WTA toggle.
- **Tournaments (`/tournaments`)** — current list, tier‑sorted. **Tournament detail (`/tournaments/[id]`)** — *Overview* (info + ATP/WTA match sections + scraped headlines) and *Threads* (the per‑match chat threads, ranked by active chatters).
- **Match detail (`/matches/[externalId]`)** *(added)* — a detail header (flags, rank, serve, approx elapsed/duration) then stacked sections **Overview → Momentum → Stats → Players → Head‑to‑head**, and (live only) **Discussion**. The Momentum chart is Chart.js with set brackets, break markers, and a hover tooltip showing the running score, serve, and in‑game points. Per‑player colors are assigned once at the page level (`lib/playerColors.ts`) and passed to every section.
- **Player detail (`/players/[id]`)** — profile, recent results (Sackmann), head‑to‑head, add‑to‑favorites.
- **Settings (`/settings`)** — manage favorites, plus an **"ATP & WTA only" display toggle** (default on; hides Challenger/ITF/junior events), persisted to localStorage. **Login (`/login`)** — auth. **Admin (`/admin`)** — the reconciliation review queue (nav link shown only to admins).
- **Digest** — the latest published "What's Worth Watching" digest is **embedded on the home page under the scores** (no separate tab), fetched from `insights/latest` and rendered with a small dependency‑free Markdown renderer (`components/Markdown.tsx`). It only appears when a digest is published, so any failure (scrape/LLM/fact‑check) gracefully falls back to scores + rankings.

Shared components in `frontend/components/`: `Nav`, `ScoresFeed`, `MatchCard`, `MatchHeader`, `MatchOverview`, `MatchMomentum`, `MatchStats`, `MatchH2H`, `MatchPlayers`, `MatchChat` (the only **SSE/`EventSource`** consumer), `MatchScoreMini`, `Flag`, `TierBadge`, `Markdown`.

State: SWR for server state; the display toggle via a small client context. No client‑side secrets — the LLM and upstream keys live only on the backend.

---

## 8. API surface

JSON over HTTPS. All times ISO‑8601 UTC. Auth is **JWT** (HMAC, 12h TTL; see §10) — a Bearer token from `/api/auth/*`. Player ids in paths/bodies are **UUIDs**; tournament ids are the local `BIGSERIAL`; matches are addressed by their api‑tennis `external_id`. Public read endpoints need no auth; `/api/me/**` need a valid token; `/api/admin/**` need `ROLE_ADMIN`.

### Auth
- `POST /api/auth/register` (email, username `^[A-Za-z0-9_]{3,20}$`, password ≥ 8) · `POST /api/auth/login` → `{ token, user }`.

### Public read — scores / rankings / players / tournaments / insights
- `GET /api/health` → liveness (separate from `/actuator/health`).
- `GET /api/scores/live` → currently live matches (`scores:live` cache). · `GET /api/scores/recent` → today's completed.
- `GET /api/rankings?tour=ATP|WTA&limit=100` → current ranking snapshot.
- `GET /api/players/{playerId}` · `…/matches?limit=20` · `…/h2h?opponentId={uuid}` → profile / results / H2H (Sackmann).
- `GET /api/tournaments/current` · `GET /api/tournaments/{id}` → list / detail.
- `GET /api/tournaments/{id}/matches` → that tournament's live+recent matches (importance‑sorted). · `…/headlines` → scraped news mentioning it (~1h cache). · `…/threads` → chat threads across its matches, most‑active first.
- `GET /api/insights/latest?type=weekly_digest` (204 if none) · `GET /api/insights/{id}` → published digest.

### Public read — match detail (one cached upstream fetch; 404 when the feed has no data)
- `GET /api/matches/{externalId}` → detail snapshot. · `…/momentum` → momentum line + breaks + set brackets. · `…/stats` → per‑period comparison. · `…/h2h` → head‑to‑head. · `…/players` → side‑by‑side bios + career splits.

### Chat (per match; cache‑only)
- `GET /api/matches/{externalId}/threads` · `…/threads/{threadId}` → thread list / detail (public).
- `POST /api/matches/{externalId}/threads` · `…/threads/{threadId}/messages` → create thread / post (**authenticated**; 423 if the match is finished).
- **SSE:** `GET /api/matches/{externalId}/threads/stream` and `…/threads/{threadId}/stream` (`text/event-stream`, public) — real‑time thread‑list / message updates. **The only SSE endpoints in the app.**

### Authenticated (user)
- `GET`/`PUT /api/me/home-config` → widget list, order, options.
- `GET /api/me/favorites` · `POST /api/me/favorites` `{playerId}` · `DELETE /api/me/favorites/{playerId}`.

### Admin (require `ROLE_ADMIN`)
- Reconciliation review: `GET /api/admin/unmapped-entities` · `…/candidates?source&externalPlayerId` · `…/upstream-matches` · `…/upstream-profile` · `POST /api/admin/entity-map`.
- Jobs: `POST /api/admin/reconcile/tier3` · `POST /api/admin/poll/{live|rankings|tournaments|recent}` · `POST /api/admin/enrichment/run`.
- Insights: `POST /api/admin/insights/generate` · `GET /api/admin/insights?status=DRAFT` · `POST /api/admin/insights/{id}/publish` · `GET /api/admin/news/preview` (debug the scraper without the LLM).

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
Email‑based auth with **stateless JWT** (HMAC‑SHA256, 12h TTL; `security/JwtService` + `SecurityConfig`). A single user role plus an `admin` flag gates the admin endpoints; the token carries `userId`, `email`, `username`, and roles. Store only what's needed: `users(id, email, username, password_hash, is_admin, created_at)` — `username` (added in `V8`) is the public handle shown on chat messages. Spring Security + BCrypt; an `AdminBootstrap` runner seeds the admin from `ADMIN_EMAIL`/`ADMIN_PASSWORD`. No third‑party login.

---

## 11. Configuration & secrets
All via environment variables / Spring profiles; nothing committed. Provide a `.env.example`.

| Var | Purpose |
|---|---|
| `TENNIS_API_BASE_URL` (`https://api.api-tennis.com/tennis/`), `TENNIS_API_KEY` | Upstream live feed (API Tennis; key is the `APIkey` query param). |
| `app.tennis-api.rate-limit-per-minute` / `-burst` / `-max-wait-seconds` | Client‑side token‑bucket safety cap on the quota (§6.1). |
| `POLL_ENABLED` (default `true`), `POLL_LIVE_INTERVAL` (`PT1M`), `POLL_RECENT_INTERVAL` (`PT15M`), `POLL_STARTUP_SYNC` (`true`) | Poll cadences + the on‑boot rankings/tournaments sync. *(Adaptive intervals deferred.)* |
| `POLL_RANKINGS_CRON`, `POLL_TOURNAMENTS_CRON`, `ENRICHMENT_JOB_CRON` (`0 20 0 * * *`), `app.digest.cron`, `app.reconcile.tier3-cron` (`0 0 7 * * *`) | Job schedules (rankings, tournaments, enrichment, weekly digest, Tier‑3). |
| `SACKMANN_ATP_DIR` / `SACKMANN_WTA_DIR`, `app.historical-load.enabled` | Local Sackmann CSV paths + the one‑time loader flag. |
| `DATABASE_URL`, `REDIS_URL` | Stores. |
| `ANTHROPIC_API_KEY` (or `LLM_API_KEY`), `LLM_MODEL`, `LLM_TIER3_MODEL` | LLM. Effective key = `LLM_API_KEY ?: ANTHROPIC_API_KEY`; gates the digest, fact‑check, and Tier‑3 (`app.reconcile.tier3-enabled`). |
| `app.news.*` | News scraper (enabled / max age / max articles) for the digest + tournament headlines. |
| `ADMIN_EMAIL` / `ADMIN_PASSWORD`, `JWT_SECRET` | Admin bootstrap + JWT signing. |

*`.env` is loaded by a custom `DotenvEnvironmentPostProcessor` (spring‑dotenv doesn't work on Boot 4); the `.env` property source sits just above `systemEnvironment` so its values resolve reliably while command‑line args still override.*

---

## 12. Data model (Postgres)

The schema below is the **as‑built** state after the `V10` unified‑schema + UUID migration and `V11` (managed by Flyway under `src/main/resources/db/migration`; **never edit an applied migration — add a new `V{n+1}`**). The defining change from the original spec: there is **no live/historical split**. A single `matches` and a single `rankings` table hold both sources, keyed by UUIDs.

```sql
-- ===== Players (loaded from Sackmann CSVs; canonical for everything reconciled) =====
CREATE TABLE players (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),  -- canonical PK everywhere (DTOs, FKs, API)
  sackmann_id      BIGINT UNIQUE NOT NULL,    -- namespaced Sackmann int: ATP = raw; WTA = raw + 1,000,000,000
  source_player_id BIGINT NOT NULL,           -- raw pre-offset Sackmann id (traceability / Tier-3 prompts)
  first_name TEXT, last_name TEXT,
  hand TEXT,                                  -- R/L/U
  birth_date DATE,
  country_code TEXT,                          -- IOC 3-letter (GER, SUI, …)
  height_cm INT,
  tour TEXT NOT NULL                          -- 'ATP' | 'WTA'
);
CREATE INDEX idx_players_name ON players (lower(last_name), lower(first_name));

-- ===== Unified matches: Sackmann history + live API-Tennis, same table =====
CREATE TABLE matches (
  id             BIGSERIAL PRIMARY KEY,
  source         TEXT NOT NULL DEFAULT 'sackmann',  -- 'sackmann' | 'api-tennis'
  external_id    TEXT,                        -- API Tennis event_key (dedup key); null for Sackmann rows
  status         TEXT NOT NULL DEFAULT 'finished', -- scheduled | live | finished
  category       TEXT,                        -- ATP | WTA | Grand Slam | Challenger | ITF | Junior (main-tour filter)
  qualifying     BOOLEAN NOT NULL DEFAULT FALSE,
  tour           TEXT NOT NULL,
  tourney_id TEXT, tourney_name TEXT, tourney_level TEXT, tourney_date DATE, match_num INT,
  round          TEXT,
  surface        TEXT,                        -- Hard/Clay/Grass (back-filled by enrichment for live rows)
  best_of        INT,
  player1_id     UUID, player2_id UUID,       -- both-player view (Sackmann: = winner/loser); null until reconciled
  winner_id      UUID, loser_id UUID,         -- Sackmann result (player1=winner, player2=loser)
  player1_name   TEXT, player2_name TEXT,     -- upstream display names
  winner_name    TEXT, loser_name TEXT,       -- denormalized Sackmann names
  score          TEXT,                        -- "6-3 4-6 7-5" (Sackmann)
  score_detail   JSONB,                       -- set-by-set JSON (API Tennis)
  serve          TEXT,                        -- 'home'|'away' (live only)
  start_time TIMESTAMPTZ, last_polled_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX idx_matches_source_external ON matches (source, external_id) WHERE external_id IS NOT NULL;
CREATE INDEX idx_matches_p1 ON matches (player1_id);  CREATE INDEX idx_matches_p2 ON matches (player2_id);
CREATE INDEX idx_matches_status ON matches (status);

-- ===== Unified rankings (surrogate PK; the natural key differs by source) =====
CREATE TABLE rankings (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  source        TEXT NOT NULL DEFAULT 'sackmann',
  ranking_date  DATE NOT NULL,
  player_id     UUID,                         -- null until reconciled (api-tennis)
  rank INT, points INT, tour TEXT NOT NULL,
  external_name TEXT,                         -- feed display name
  captured_at   TIMESTAMPTZ                   -- exact poll time; null for Sackmann
);
-- Sackmann ranks TIE (several players share a rank/date) → key is (…, player_id); the live feed has
-- unique ranks but possibly-null player_id → key is (…, rank). Two PARTIAL unique indexes, NOT one PK:
CREATE UNIQUE INDEX uq_rankings_sackmann ON rankings (source, ranking_date, tour, player_id) WHERE source = 'sackmann';
CREATE UNIQUE INDEX uq_rankings_live     ON rankings (source, ranking_date, tour, rank)      WHERE source <> 'sackmann';

CREATE TABLE tournaments (
  id BIGSERIAL PRIMARY KEY, source TEXT NOT NULL, external_id TEXT NOT NULL,
  name TEXT NOT NULL, level TEXT, surface TEXT, location TEXT, tour TEXT,
  start_date DATE, end_date DATE, draw JSONB,
  UNIQUE (source, external_id)
);

-- Reconciliation source of truth (+ signals so the offline Tier-3 LLM can re-derive candidates)
CREATE TABLE entity_map (
  source TEXT NOT NULL, external_player_id TEXT NOT NULL, external_name TEXT,
  player_id UUID,                             -- null = unmapped/needs review (soft ref, not FK)
  confidence REAL, confirmed BOOLEAN NOT NULL DEFAULT FALSE,
  tier TEXT,                                  -- CACHE | DETERMINISTIC | RULES | LLM | MANUAL (auditability)
  rationale TEXT,
  tour TEXT, country_code TEXT, rank_hint INT, birth_year INT,  -- V7/V11 disambiguation signals
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (source, external_player_id)
);

-- Async enrichment work-list (currently tournament surface; V10)
CREATE TABLE enrichment_queue (
  entity_type TEXT NOT NULL, entity_id TEXT NOT NULL,   -- e.g. ('tournament', '<id>')
  fields_needed TEXT[] NOT NULL,                        -- e.g. {surface}
  status TEXT NOT NULL DEFAULT 'pending',               -- pending | in_progress | done | exhausted
  attempts INT NOT NULL DEFAULT 0,
  last_attempted_at TIMESTAMPTZ, resolved_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (entity_type, entity_id)
);

CREATE TABLE generated_insights (
  id BIGSERIAL PRIMARY KEY, type TEXT NOT NULL, title TEXT NOT NULL, body_markdown TEXT NOT NULL,
  source_data JSONB NOT NULL,                  -- the fact sheet used (no scraped-article data persisted)
  model TEXT, status TEXT NOT NULL DEFAULT 'DRAFT', generated_at TIMESTAMPTZ NOT NULL, published_at TIMESTAMPTZ
);

-- ===== User =====
CREATE TABLE users (
  id BIGSERIAL PRIMARY KEY, email TEXT UNIQUE NOT NULL,
  username TEXT,                              -- V8; unique on lower(username); the public chat handle
  password_hash TEXT NOT NULL, is_admin BOOLEAN NOT NULL DEFAULT FALSE, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX idx_users_username ON users (lower(username));
CREATE TABLE user_home_config (user_id BIGINT PRIMARY KEY REFERENCES users(id), layout JSONB NOT NULL);
CREATE TABLE user_favorites (
  user_id BIGINT NOT NULL REFERENCES users(id), player_id UUID NOT NULL REFERENCES players(id),
  PRIMARY KEY (user_id, player_id)
);
```

> **Gone since V10:** `live_matches` (→ `matches`), `live_rankings` (→ `rankings`), `rankings_history` (renamed → `rankings`), and the BIGINT `players.player_id` (→ UUID `players.id`, with the integer preserved as `sackmann_id`).

### Redis keys (read‑through cache over the tables above)
- `scores:live` / `scores:recent` — current live / recently‑completed matches.
- `rankings:atp` / `rankings:wta` — current ranking snapshots. · `tournaments:current` — current list (24h TTL).
- `matchdetail:v5:{source}:{externalId}` — normalized point‑by‑point + stats (versioned; 30s live / 12h finished / 5‑min empty). `h2h:*` / `players:*` — the H2H / Players tabs (same status‑based TTL).
- `chat:{matchId}:threads` (hash) · `chat:{matchId}:t:{threadId}` (list) · `…:chatters` (zset) — the cache‑only chat, 1‑day TTL, never persisted.

The serving API reads these first; on a miss it falls back to the corresponding Postgres table (and repopulates).

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
- **Phase 7 — Polish.** 🚧 In progress. ✅ Done: admin reconciliation review UI (`/admin` page + `GET /api/admin/unmapped-entities/candidates`, reusing `CandidateFinder`); upstream-outage resilience (adapter throws `UpstreamApiException` on error envelopes via `resultOf`, pollers `runCatching` and stores skip empty writes, so the Redis/Postgres read path serves last-good); upstream rate limiting (`UpstreamRateLimiter` token bucket as a `RestClient` interceptor, `app.tennis-api.rate-limit-*`). ⏳ Remaining: the **enrichment LLM pass** (deterministic-failure → LLM → exhaustion) and optional adaptive cadence / draws.

- **Unified schema + UUID migration (added milestone).** ✅ Done (`V10`/`V11`). Collapsed `live_matches`/`live_rankings`/`rankings_history` into the single accumulating `matches`/`rankings` tables (with a `source` column + source-scoped partial unique indexes), and moved the canonical player key from a namespaced BIGINT to a `UUID` (`players.id`), keeping `sackmann_id` for traceability/Tier-3. Includes the `enrichment_queue` table and the one-time `scripts/dedupe-players.sql` cleanup.
- **Match detail + momentum view (added milestone).** ✅ Done. `match/MatchDetailService` (one cached `get_fixtures&match_key` call → Momentum/Stats/H2H/Players tabs) and the bespoke `MomentumCalculator` (point-by-point reconstruction, match-state leverage, set-won impulses, stamina regression, tiebreak mini-set). Frontend `/matches/[externalId]` with the Chart.js momentum graph.
- **Per-match live chat over SSE (added milestone).** ✅ Done. `chat/ChatStore` (Redis-only, 1-day TTL) + `chat/ChatEventHub` (`SseEmitter`); public read streams, authenticated POSTs, threads lock on match finish. Surfaced on the match Discussion section and the tournament *Threads* tab.
- **Tournament surface enrichment (added milestone).** ✅ Done (deterministic). `enrichment/` queue + `EnrichmentJob`/`DeterministicEnricher`, `SurfaceResolver` over the curated registry and the upstream `get_tournaments` catalog; write-time fill in `TournamentSyncJob` with the residue queued.

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
