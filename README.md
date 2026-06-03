# Tennis Companion

A companion app for tennis fans to **learn about and enjoy** the sport — poll-based scores &
rankings, player/tournament detail backed by free historical data, and one AI-generated weekly
"What's Worth Watching" digest. Not a betting, real-time, or chatbot product.

Backend: **Kotlin + Spring Boot 4.0** (JVM 21), Postgres 16, Redis 7, Flyway. Frontend:
**Next.js 16** (App Router, TypeScript, SWR). See the design docs for the full spec.

> **Status:** Phases 0–6b complete — historical foundation (Sackmann loader + player/H2H),
> live scores & rankings (poll + reconcile + Redis fan-out), tournaments, users/auth (JWT),
> the Next.js frontend, the **weekly AI "What's Worth Watching" digest** (offline batch,
> grounded in DB facts, DRAFT → manual publish — Anthropic Claude via `LLM_API_KEY`/`ANTHROPIC_API_KEY`)
> with its **`/insights` page**, and reconciliation **Tier 3** (an offline LLM pass over the review queue).
> Next: Phase 7 (polish).

## Prerequisites
- **JDK 21** (Temurin). This repo uses the Gradle wrapper (`./gradlew`), which respects `JAVA_HOME`.
- **Docker** (for Postgres + Redis, and for the Testcontainers-backed tests).

## Local development
```bash
# 1. start infrastructure
docker compose up -d postgres redis

# 2. run the backend (Flyway migrates on boot; pollers start on their schedule)
./gradlew bootRun        # Windows: .\gradlew.bat bootRun

# 3. verify
curl http://localhost:8080/api/health           # -> {"status":"UP",...}
curl http://localhost:8080/actuator/health      # -> {"status":"UP","components":{db..,redis..}}
```

Run the test suite (spins up throwaway Postgres + Redis via Testcontainers — Docker must be running):
```bash
./gradlew build
```

Configuration is via environment variables (see `.env.example`); local defaults match
`docker-compose.yml`, so the app boots with no `.env` needed.

## Frontend
A Next.js 16 app in `frontend/` (App Router, TypeScript, SWR, plain CSS — dark theme). Pages:
home dashboard, live scores, rankings (ATP/WTA), player profiles (rank + Sackmann match history),
tournaments, login/register, and settings (favorites). Auth uses the backend JWT; favorites and
the personalized home are token-gated.
```bash
cd frontend
npm install
npm run dev        # http://localhost:3000
```
The API base URL is `NEXT_PUBLIC_API_URL` (see `frontend/.env.local`, default `http://localhost:8080`).
The backend allows the frontend origin via CORS (`app.cors.allowed-origins`, default `http://localhost:3000`).

## Project layout
```
src/main/kotlin/com/tenniscompanion/
  config/        Spring configuration
  integration/   upstream live-API adapter (all provider specifics isolated here)
  poller/        @Scheduled pollers (live scores, rankings, tournaments)
  loader/        one-time historical (Sackmann CSV) loader
  reconcile/     entity reconciliation (upstream IDs <-> Sackmann IDs)
  insight/       AI weekly-digest job + LlmClient
  api/           REST controllers
  domain/        domain types / entities
src/main/resources/db/migration/   Flyway migrations
```

## Attribution
Historical data derived from **Jeff Sackmann / Tennis Abstract** datasets, licensed
**CC BY-NC-SA 4.0** — this is a non-commercial / portfolio build. Live data courtesy of
**API Tennis (api-tennis.com)**. These credits appear in the in-app footer on every page.
