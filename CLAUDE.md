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

## Frontend routes

App Router pages under `frontend/app/`:
- `/` — home (live scores feed + embedded weekly digest when published)
- `/scores` — full scores feed
- `/rankings` — ATP/WTA rankings
- `/tournaments` — tournament list
- `/matches/[externalId]` — match detail + live chat (SSE)
- `/players/[id]` — player profile + H2H
- `/login`, `/settings` — auth pages

Shared components in `frontend/components/`: `Nav`, `ScoresFeed`, `MatchCard`, `MatchChat`, `MatchHeader`, `Flag`, `TierBadge`, `Markdown`.

## Test structure

All tests are under `src/test/kotlin/com/tenniscompanion/`. Integration tests (those that use Testcontainers for Postgres + Redis) pull in `TestcontainersConfiguration` — Docker must be running for these. Unit tests (e.g. `NameNormalizerTest`, `MatchWeightingTest`, `DigestParsingTest`) have no external dependencies and run standalone.
