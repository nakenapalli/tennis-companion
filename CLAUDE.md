# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

@AGENTS.md

## Build & run (Linux)

> The commands in AGENTS.md use Windows PowerShell (`.\gradlew.bat`). On Linux use `./gradlew` instead:

```bash
# Infrastructure (Postgres 5432, Redis 6379)
docker compose up -d postgres redis

# Backend
./gradlew build          # compile + all tests (Testcontainers — Docker must be running)
./gradlew bootRun        # :8080

# Run a single test class
./gradlew test --tests "com.tenniscompanion.reconcile.NameNormalizerTest"

# Run the backend on an alternate port so it doesn't collide with a running instance
SERVER_PORT=8081 ./gradlew bootRun

# One-time Sackmann historical load (idempotent)
./gradlew bootRun --args='--app.historical-load.enabled=true'

# Frontend
cd frontend && npm install && npm run dev   # :3000
npm run build                              # type-check
```

### Quick dev environment (`./dev.sh`)

`./dev.sh` spins the **app** processes (backend :8080 + frontend :3000) up and down together for a test
environment. It does **not** manage infra — keep Postgres/Redis running for your session
(`docker compose up -d postgres redis`); `up` only checks they're reachable and bails with a hint if not.

```bash
./dev.sh up        # start backend + frontend, wait until both answer
./dev.sh down      # stop both (infra left running)
./dev.sh restart
./dev.sh status    # what's running + infra reachability
./dev.sh logs [backend|frontend]   # tail -F (both if no arg)
```

Each process runs in its own session (`setsid`/`nohup`) so it outlives the script and `down` can kill the
whole tree; pids + logs live in `.dev/` (gitignored).

## Data model & historical (Sackmann) data

**The Sackmann repos (`JeffSackmann/tennis_atp` / `tennis_wta`) are no longer publicly accessible** — the
`git clone` commands in `AGENTS.md` / `docs/tennis-app-data-setup-briefer.md` are stale and will fail. Seed
the historical load from a **local copy** of the CSVs in `data/tennis_atp` and `data/tennis_wta` (override
the paths with `SACKMANN_ATP_DIR` / `SACKMANN_WTA_DIR`; see `application.yml`). This load is the prerequisite
for everything reconciled: without players loaded, nothing reconciles, so live matches show **no country,
rank, or player links** — the live feed carries none of those, they're only attached by mapping a match's
players onto reconciled Sackmann player rows.

**Unified, accumulating model.** Live API Tennis data is **not** kept ephemeral or separate from the
Sackmann history — both share **one unified schema** (the `matches` / `rankings` tables). The Sackmann load
lays down the historical base; ongoing API Tennis polling is **enriched and upserted on top of it**, so the
DB is a **running history** rather than a transient live snapshot (this is the "unify schema + enrichment
job" work). Reconciliation maps the upstream player ids onto the canonical Sackmann player set; Redis stays
a pure read cache over these tables.

## Flyway migrations — never edit an applied migration

Flyway validates each migration's checksum on startup (`validate-on-migrate` is on by default, not disabled
in `application.yml`). **Once a `V*__*.sql` file has been applied to any DB, editing it is a hard error** —
the recomputed checksum won't match `flyway_schema_history`, so the next `./gradlew bootRun`/`build` fails
validation and the app won't boot. Worse, the live schema still reflects the *old* file, so even bypassing
validation leaves runtime mismatches (e.g. an `ON CONFLICT` naming an index the old migration never created).

**Always add a new `V{n+1}` migration to change the schema — never modify a committed one.** If an applied
migration was edited (e.g. pulled from a branch where it changed in place), the dev fix is to recreate the
volume from scratch, since the historical data is reloadable:

```bash
docker compose down && docker volume rm tennis-companion_tc-pgdata
docker compose up -d postgres redis
./gradlew bootRun --args='--app.historical-load.enabled=true'   # runs migrations clean + loads Sackmann
docker exec -i tc-postgres psql -U tennis -d tennis < scripts/dedupe-players.sql
```

## Frontend routes

App Router pages under `frontend/app/`:
- `/` — home (live scores feed + embedded weekly digest when published)
- `/scores` — full scores feed
- `/rankings` — ATP/WTA rankings
- `/tournaments` — tournament list
- `/tournaments/[id]` — tournament detail (Overview: info + ATP/WTA matches + headlines; Threads: per-match chat threads ranked by active chatters)
- `/matches/[externalId]` — match detail + live chat (SSE)
- `/players/[id]` — player profile + H2H
- `/login`, `/settings` — auth pages

Shared components in `frontend/components/`: `Nav`, `ScoresFeed`, `MatchCard`, `MatchChat`, `MatchHeader`, `Flag`, `TierBadge`, `Markdown`.

## Test structure

All tests are under `src/test/kotlin/com/tenniscompanion/`. Integration tests (those that use Testcontainers for Postgres + Redis) pull in `TestcontainersConfiguration` — Docker must be running for these. Unit tests (e.g. `NameNormalizerTest`, `MatchWeightingTest`, `DigestParsingTest`) have no external dependencies and run standalone.
