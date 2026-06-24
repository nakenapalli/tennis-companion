# Tennis Companion — Data Setup Briefer

> Companion to `tennis-app-design-doc.md`. A short, practical guide to the two data sources the MVP needs: a **live-ish feed** (scores, rankings, fixtures) and the **free historical datasets** (career results, head-to-head). Intended for the build session to walk through with the developer during Phase 1–2 setup.
>
> Pricing and tiers below are approximate and change frequently — **confirm current numbers at signup**. The recommended starting path uses free trials / free tiers, so you can build the whole MVP before paying anything.

---

## 1. Live data feed

### The shape of the decision
Real-time, sub-second, point-by-point feeds (the kind sportsbooks use) are expensive — full tennis packages from providers like Goalserve run on the order of ~$1000/month, and SportRadar / SportsDataIO are enterprise-priced. **The MVP does not need any of that.** Because the app is poll-based (minutes, not seconds) and reads scores/rankings/fixtures rather than live odds, it fits comfortably in the cheap end of the market — and the server-side-poll-and-fan-out design (design doc §4) keeps request volume tied to matches being played, not user count.

### As built: API Tennis (api-tennis.com)
> The MVP shipped on **API Tennis (api-tennis.com)** — used *directly*, not via RapidAPI. (It first ran on a RapidAPI provider; SportDevs went offline, and API Tennis offered better data + a far bigger quota, so the adapter was swapped — exactly the cheap swap the §6.1 isolation was for.) The "affordable cluster" table below is kept for context; `tennis-api.com` in it is essentially this provider.

**Key facts for API Tennis:**
- **Base + auth:** `https://api.api-tennis.com/tennis/?method=<m>&APIkey=<key>` — auth is an **`APIkey` query parameter**, not a header.
- **Quota:** Starter ≈ **8,000 requests/day** (Premium 80k, Business 200k); a **14-day trial** is available. That's ~160× the old free-tier cap, which is why scheduled polling is on by default (a 60s live poll ≈ 1,440/day).
- **Methods used:** `get_livescore` (live), `get_standings&event_type=ATP|WTA` (rankings), `get_fixtures&date_start&date_stop` (matches → current tournaments + recently-completed). For the **match-detail view**: `get_fixtures&match_key=…` (returns the point-by-point flow *and* the undocumented `statistics` array — powers momentum + stats), `get_H2H` (head-to-head when a player isn't reconciled), `get_players&player_key=…` (career splits / logo). For **surface enrichment**: `get_tournaments` (a static reference catalog — the only place the feed exposes a surface, misspelled `tournament_sourface`, keyed by `tournament_key` = our `external_id`). For **admin reconciliation review**: `get_fixtures&player_key=…` (a candidate's recent results). H2H still prefers Sackmann history when both players are reconciled.
- **Response gotchas the adapter handles:** snake_case fields; ids come as JSON **numbers** (Jackson coerces to our `String?`); full country **names** ("Serbia") → mapped to IOC codes; **no surface** in the live/fixtures feed (only the `get_tournaments` catalog has it, and it's imperfect — a curated registry overrides it); tiebreak sets encoded as decimals ("7.7" = 7 games / 7 TB pts), and a tiebreak appears **twice** in the point-by-point (as the set's deciding game *and* a separate `"Set N TieBreak"` per-point list); round is mixed ("1/8-finals" *and* "Quarter-finals"); a 0-result livescore returns `{"success":1}` with no `result` key (the adapter treats a missing/error envelope as a failure, not an empty result, so a glitch never wipes last-good rows).
- WebSocket (`wss://wss.api-tennis.com/live`) exists but is **Business-tier** and unnecessary for minute-level freshness.

### Realistic options (historical context — verify current pricing at signup)

| Provider | Coverage | Free option | Paid entry | Notes |
|---|---|---|---|---|
| **API Tennis (api-tennis.com)** ← used | Live scores, rankings, fixtures, H2H, odds | 14-day trial | ~$40/mo Starter (8k req/day) | What the MVP shipped on. Direct, not via RapidAPI. |
| SportDevs / "Tennis Devs" | ATP/WTA/ITF, scores, rankings, odds | (was) free trial | — | **Went offline** — site/API down; not usable. |
| Tennis Live Data (sportcontentapi, via RapidAPI) | Live scores, fixtures | small free cap | ~$29–$49/mo | Typical RapidAPI tiered pricing. |

Keep all provider-specific code behind the single adapter (design doc §6.1) so swapping stays cheap.

### What to actually do at setup
1. Sign up at **api-tennis.com**, start the trial, and copy your **APIkey**.
2. Put it in `TENNIS_API_KEY` and set `TENNIS_API_BASE_URL=https://api.api-tennis.com/tennis/` (design doc §11). Never commit; `.env.example` has the names only. (The `.env` loader sits above OS env vars so the value resolves reliably.)
3. Hit `get_livescore` / `get_standings` / `get_fixtures` by hand (curl) to capture **real sample responses** before/while writing the adapter — the shape drives `NormalizedMatch`/`NormalizedRanking` mapping and the reconciliation fields (country, rank hints).
4. The big quota means cadence is comfortable: live 60s, rankings/fixtures daily — well under 8k/day (design doc §6.2).

### ⚠️ Read the Terms of Service first
Each provider's ToS governs how you may **store, cache, display, and redistribute** their data, and some restrict building a competing scores product. Read it before building the UI around the feed. This is separate from the historical-data license below.

---

## 2. Historical datasets (Jeff Sackmann / Tennis Abstract)

### What they are
A free, high-quality, community-standard set of tennis data published as CSV files on GitHub. They are the backbone of the app's "learn and revisit" content — career results, rankings history, and head-to-head.

The repos that matter for the MVP:
- `JeffSackmann/tennis_atp` — ATP master player file, historical rankings (mostly complete 1985–present), match results and stats, plus a `matches_data_dictionary.txt` explaining the columns.
- `JeffSackmann/tennis_wta` — the WTA equivalent, same file format.

Other repos in the same account (`tennis_pointbypoint`, `tennis_MatchChartingProject`, `tennis_slam_pointbypoint`) are out of scope for the MVP but worth knowing about for later features.

### File layout you'll load
- Player file: `player_id, first_name, last_name, hand, birth_date, country_code, height` → maps to `players`. `country_code` is **IOC** 3-letter (GER, SUI, …) — relevant for reconciliation country matching.
- Rankings files (per period): `ranking_date, ranking, player_id, ranking_points` → maps to `rankings` (`source='sackmann'`).
- Match files per season (e.g. `atp_matches_2014.csv`): winner/loser, surface, tourney info, round, score → maps to `matches` (`source='sackmann'`).

> ⚠️ **As-built gotchas — IDs.** (1) **ATP and WTA raw `player_id`s collide** (~14.5k overlapping). The loader namespaces an integer key as **ATP = raw, WTA = raw + 1,000,000,000**, keeping the raw value in `source_player_id`. (2) **The canonical PK is now a `UUID` (`players.id`)** after the V10 migration — that namespaced integer lives on as `players.sackmann_id` (still carrying the WTA offset, used for traceability + the Tier-3 LLM prompts). Everything reconciled (`matches`, `rankings`, `entity_map`, `user_favorites`) references the **UUID**. (3) Live API-Tennis rows accumulate into those *same* `matches`/`rankings` tables (`source='api-tennis'`) — the Sackmann load lays the base, the pollers upsert on top (design doc §12).

For the MVP, load the **most recent ~5 seasons** plus the player and rankings files (the as-built loader did 2021–2026); the schema supports the full archive later (design doc §6.3, §12). It's idempotent (`ON CONFLICT DO NOTHING`). **After a fresh load, run the dedupe once:** `docker exec -i tc-postgres psql -U tennis -d tennis < scripts/dedupe-players.sql` — the CSVs list ~226 players under multiple ids and the load re-introduces them every time (it's a no-op on an already-clean DB).

### How to get them
> ⚠️ **The `JeffSackmann/tennis_atp` and `tennis_wta` repos are no longer publicly accessible**, so the `git clone` commands below will fail. Seed the load from a **local copy** of the CSVs placed in `data/tennis_atp` and `data/tennis_wta` (override the paths with `SACKMANN_ATP_DIR` / `SACKMANN_WTA_DIR`; defaults are in `application.yml`).

The original repos (for reference / if you already have a copy):
```
git clone https://github.com/JeffSackmann/tennis_atp.git    # no longer public
git clone https://github.com/JeffSackmann/tennis_wta.git    # no longer public
```
Point the historical loader (design doc §6.3) at the local CSV paths via the env vars above. Keep the loader **idempotent** so re-running it (e.g. after pulling newer data) doesn't duplicate rows.

### ⚠️ License — the one that shapes commercialization
These datasets are licensed **Creative Commons Attribution-NonCommercial-ShareAlike 4.0**:
- **Attribution is required** — credit Jeff Sackmann / Tennis Abstract on an in-app About/Credits screen (design doc §16).
- **NonCommercial** — fine for this personal/portfolio MVP, but charging, ads, or paid feature-gating likely falls outside the license. A commercial launch would require a properly licensed feed instead. Do not design any paywall into the MVP (design doc §2, §15).

---

## 3. What the build session should do with this

**Sequencing note:** although this briefer presents the live API first, do the **historical setup first**. The Sackmann data needs no account and no ToS review, so it unblocks real work immediately — you get real rows to build and test the player endpoints against (Phase 1) right away. The live feed depends on signup and reading the provider's terms, so front-loading the dependency-free part keeps you moving while that's sorted.

1. Place a local copy of the Sackmann CSVs in `data/tennis_atp` / `data/tennis_wta` (the repos are no longer public — see §2) and confirm the historical loader can read them; load the recent seasons + player/rankings files, then run `scripts/dedupe-players.sql`. Proceed into Phase 1 (player endpoints) against real data.
2. Sign up at **api-tennis.com** (14-day trial), capture sample responses from `get_livescore`/`get_standings`/`get_fixtures`, and wire `TENNIS_API_KEY` + `TENNIS_API_BASE_URL` into env vars.
3. Surface the provider's ToS so the developer can read it before the adapter is built.
4. Proceed into Phase 2 (adapter + pollers + reconciliation Tiers 0–2).
