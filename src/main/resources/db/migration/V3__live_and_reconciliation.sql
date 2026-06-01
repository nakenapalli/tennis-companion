-- Phase 2: live/app-managed tables (synced from upstream) + the reconciliation store (design §12).
-- unaccent powers accent-insensitive name matching in the reconciliation candidate query.
CREATE EXTENSION IF NOT EXISTS unaccent;

CREATE TABLE tournaments (
    id          BIGSERIAL PRIMARY KEY,
    source      TEXT NOT NULL,
    external_id TEXT NOT NULL,
    name        TEXT NOT NULL,
    level       TEXT,
    surface     TEXT,
    location    TEXT,
    tour        TEXT,
    start_date  DATE,
    end_date    DATE,
    draw        JSONB,
    UNIQUE (source, external_id)
);

CREATE TABLE live_matches (
    id             BIGSERIAL PRIMARY KEY,
    source         TEXT NOT NULL,
    external_id    TEXT NOT NULL,
    tournament_id  BIGINT REFERENCES tournaments(id),
    status         TEXT NOT NULL,              -- scheduled | live | finished
    round          TEXT,
    surface        TEXT,
    tour           TEXT,
    tournament_name TEXT,                       -- denormalized for display (tournaments table optional in MVP)
    player1_name   TEXT NOT NULL,              -- upstream display name (always present)
    player2_name   TEXT NOT NULL,
    player1_id     BIGINT,                     -- soft ref to players; null until reconciled
    player2_id     BIGINT,
    score          JSONB,
    start_time     TIMESTAMPTZ,
    last_polled_at TIMESTAMPTZ NOT NULL,
    UNIQUE (source, external_id)
);
CREATE INDEX idx_live_status ON live_matches (status);

CREATE TABLE live_rankings (
    tour          TEXT NOT NULL,
    rank          INT NOT NULL,
    player_id     BIGINT,                      -- null until reconciled
    external_name TEXT NOT NULL,
    points        INT,
    captured_at   TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tour, rank, captured_at)
);

-- Reconciliation source of truth: (source, external_player_id) -> canonical player_id.
CREATE TABLE entity_map (
    source             TEXT NOT NULL,
    external_player_id TEXT NOT NULL,
    external_name      TEXT,
    player_id          BIGINT,                 -- null = unmapped / needs human review
    confidence         REAL,
    confirmed          BOOLEAN NOT NULL DEFAULT FALSE,
    tier               TEXT,                   -- CACHE | DETERMINISTIC | RULES | LLM (auditability)
    rationale          TEXT,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (source, external_player_id)
);
-- partial index over the review queue (unconfirmed mappings)
CREATE INDEX idx_entity_map_review ON entity_map (confirmed) WHERE NOT confirmed;
