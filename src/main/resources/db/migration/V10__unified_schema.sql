-- V10: Unified canonical schema.
--
-- Merges the two matches tables (live_matches + matches) and two rankings tables
-- (live_rankings + rankings_history) into single canonical tables per entity.
-- Data from any source (Sackmann CSVs, API Tennis, future providers) is written
-- directly into these tables; Redis remains a pure read cache.
--
-- Also replaces Sackmann-namespaced BIGINT player IDs with UUIDs throughout,
-- removing the hard dependency on Sackmann's player numbering scheme.
--
-- Adds enrichment_queue for the async field-enrichment pipeline (Phase 2).

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ─── 1. UNIFIED matches ────────────────────────────────────────────────────
-- Add the fields needed to track live/scheduled API Tennis matches alongside
-- the existing finished Sackmann historical rows.

ALTER TABLE matches
    ADD COLUMN source         TEXT        NOT NULL DEFAULT 'sackmann',
    ADD COLUMN external_id    TEXT,                            -- API Tennis event_key; null for Sackmann rows
    ADD COLUMN status         TEXT        NOT NULL DEFAULT 'finished', -- scheduled|live|finished
    ADD COLUMN category       TEXT,                            -- ATP|WTA|Grand Slam|Challenger|ITF|Junior
    ADD COLUMN qualifying     BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN player1_id     UUID,                            -- both-player view (UUID, populated after player migration)
    ADD COLUMN player2_id     UUID,
    ADD COLUMN player1_name   TEXT,                            -- upstream display name
    ADD COLUMN player2_name   TEXT,
    ADD COLUMN score_detail   JSONB,                           -- set-by-set JSON (API Tennis); null for Sackmann
    ADD COLUMN start_time     TIMESTAMPTZ,                     -- actual match start; null for Sackmann rows
    ADD COLUMN last_polled_at TIMESTAMPTZ,
    ADD COLUMN serve          TEXT;                            -- 'home'|'away'; live matches only

-- Back-fill display names for existing Sackmann rows
UPDATE matches SET player1_name = winner_name, player2_name = loser_name;

-- Partial unique index for API Tennis deduplication (Sackmann rows use the existing
-- UNIQUE(tour, tourney_id, match_num) natural key which stays intact)
CREATE UNIQUE INDEX idx_matches_source_external ON matches (source, external_id)
    WHERE external_id IS NOT NULL;

-- live_matches is ephemeral; rows repopulate on the next poll cycle
DROP TABLE live_matches;

-- ─── 2. UNIFIED rankings ───────────────────────────────────────────────────

ALTER TABLE rankings_history RENAME TO rankings;

-- Add provenance and API Tennis-specific fields before dropping the old PK
ALTER TABLE rankings
    ADD COLUMN source        TEXT        NOT NULL DEFAULT 'sackmann',
    ADD COLUMN external_name TEXT,                             -- player display name from the feed
    ADD COLUMN captured_at   TIMESTAMPTZ;                      -- exact poll timestamp; null for Sackmann rows

-- live_rankings is ephemeral; rows repopulate on the next poll cycle
DROP TABLE live_rankings;

-- ─── 3. UUID PLAYER IDs — players table ────────────────────────────────────

ALTER TABLE players ADD COLUMN id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE players RENAME COLUMN player_id TO sackmann_id;
ALTER TABLE players DROP CONSTRAINT players_pkey;
ALTER TABLE players ADD PRIMARY KEY (id);
ALTER TABLE players ADD UNIQUE (sackmann_id);

-- ─── 4. UUID REFERENCES — matches ─────────────────────────────────────────

ALTER TABLE matches
    ADD COLUMN winner_uuid UUID,
    ADD COLUMN loser_uuid  UUID;

UPDATE matches SET winner_uuid = p.id FROM players p WHERE matches.winner_id = p.sackmann_id;
UPDATE matches SET loser_uuid  = p.id FROM players p WHERE matches.loser_id  = p.sackmann_id;

-- For Sackmann rows, player1 = winner and player2 = loser (winner is always listed first in CSVs)
UPDATE matches SET player1_id = winner_uuid, player2_id = loser_uuid WHERE source = 'sackmann';

-- Drop old BIGINT columns (automatically drops idx_matches_winner and idx_matches_loser)
ALTER TABLE matches DROP COLUMN winner_id, DROP COLUMN loser_id;
ALTER TABLE matches RENAME COLUMN winner_uuid TO winner_id;
ALTER TABLE matches RENAME COLUMN loser_uuid  TO loser_id;

-- Recreate indexes on the new UUID columns
CREATE INDEX idx_matches_winner  ON matches (winner_id);
CREATE INDEX idx_matches_loser   ON matches (loser_id);
CREATE INDEX idx_matches_p1      ON matches (player1_id);
CREATE INDEX idx_matches_p2      ON matches (player2_id);
CREATE INDEX idx_matches_status  ON matches (status);

-- ─── 5. UUID REFERENCES — rankings ────────────────────────────────────────

-- Drop the old PK (was on ranking_date, player_id BIGINT, tour) before changing player_id type
ALTER TABLE rankings DROP CONSTRAINT rankings_history_pkey;

ALTER TABLE rankings ADD COLUMN player_uuid UUID;
UPDATE rankings SET player_uuid = p.id FROM players p WHERE rankings.player_id = p.sackmann_id;
-- idx_rankings_player is on player_id; dropping the column drops the index automatically
ALTER TABLE rankings DROP COLUMN player_id;
ALTER TABLE rankings RENAME COLUMN player_uuid TO player_id;

-- New PK: unique ranking position per source, day, and tour
ALTER TABLE rankings ADD PRIMARY KEY (source, ranking_date, tour, rank);
CREATE INDEX idx_rankings_player ON rankings (player_id, tour);

-- ─── 6. UUID REFERENCES — entity_map ──────────────────────────────────────
-- player_id is nullable here (null = unmapped); null rows stay null after migration.

ALTER TABLE entity_map ADD COLUMN player_uuid UUID;
UPDATE entity_map SET player_uuid = p.id FROM players p WHERE entity_map.player_id = p.sackmann_id;
ALTER TABLE entity_map DROP COLUMN player_id;
ALTER TABLE entity_map RENAME COLUMN player_uuid TO player_id;

-- ─── 7. UUID REFERENCES — user_favorites ──────────────────────────────────
-- player_id is part of the PK, so drop and rebuild it.

ALTER TABLE user_favorites DROP CONSTRAINT user_favorites_pkey;
ALTER TABLE user_favorites ADD COLUMN player_uuid UUID;
UPDATE user_favorites SET player_uuid = p.id FROM players p WHERE user_favorites.player_id = p.sackmann_id;
ALTER TABLE user_favorites DROP COLUMN player_id;
ALTER TABLE user_favorites RENAME COLUMN player_uuid TO player_id;
ALTER TABLE user_favorites ADD PRIMARY KEY (user_id, player_id);
ALTER TABLE user_favorites ADD FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE;

-- ─── 8. enrichment_queue ──────────────────────────────────────────────────
-- Tracks async enrichment tasks (e.g. surface lookup for tournaments). One row
-- per entity; status cycles pending → in_progress → done|exhausted.

CREATE TABLE enrichment_queue (
    entity_type       TEXT        NOT NULL,   -- 'tournament' | 'player' | 'match'
    entity_id         TEXT        NOT NULL,   -- bigint id for tournament, UUID string for player/match
    fields_needed     TEXT[]      NOT NULL,   -- e.g. ARRAY['surface']
    status            TEXT        NOT NULL DEFAULT 'pending',
    attempts          INT         NOT NULL DEFAULT 0,
    last_attempted_at TIMESTAMPTZ,
    resolved_at       TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (entity_type, entity_id)
);
