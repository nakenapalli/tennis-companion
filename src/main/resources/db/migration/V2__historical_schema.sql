-- Phase 1: historical/reference tables, loaded from Jeff Sackmann's tennis_atp / tennis_wta CSVs.
-- (design doc §12, refined per real data.)
--
-- NOTE on player_id: ATP and WTA Sackmann ids overlap (~14.5k colliding ids), so a single global
-- player_id requires namespacing. Canonical id = ATP: raw Sackmann id; WTA: raw id + 1_000_000_000.
-- `source_player_id` keeps the raw Sackmann id for traceability / later reconciliation.

CREATE TABLE players (
    player_id        BIGINT PRIMARY KEY,        -- canonical (namespaced) id
    source_player_id BIGINT NOT NULL,           -- raw Sackmann id
    first_name       TEXT,
    last_name        TEXT,
    hand             TEXT,                       -- R / L / U
    birth_date       DATE,
    country_code     TEXT,                       -- IOC 3-letter
    height_cm        INT,
    tour             TEXT NOT NULL               -- 'ATP' | 'WTA'
);
CREATE INDEX idx_players_name ON players (lower(last_name), lower(first_name));

CREATE TABLE rankings_history (
    ranking_date DATE   NOT NULL,
    player_id    BIGINT NOT NULL,                -- canonical id (soft ref: rankings load before all players guaranteed)
    rank         INT,
    points       INT,
    tour         TEXT   NOT NULL,
    PRIMARY KEY (ranking_date, player_id, tour)
);
CREATE INDEX idx_rankings_player ON rankings_history (player_id, tour);

CREATE TABLE matches (
    id            BIGSERIAL PRIMARY KEY,
    tour          TEXT NOT NULL,
    tourney_id    TEXT,
    tourney_name  TEXT,
    surface       TEXT,                          -- Hard / Clay / Grass / Carpet
    tourney_level TEXT,
    tourney_date  DATE,
    match_num     INT,
    round         TEXT,
    best_of       INT,
    winner_id     BIGINT,                        -- canonical id (soft ref; Sackmann match files occasionally
    loser_id      BIGINT,                        --   reference players absent from the player file)
    winner_name   TEXT,                          -- denormalized from the CSV so display works without a join
    loser_name    TEXT,
    score         TEXT,                          -- e.g. "6-3 4-6 7-5"
    UNIQUE (tour, tourney_id, match_num)         -- natural key → idempotent re-load
);
CREATE INDEX idx_matches_winner ON matches (winner_id);
CREATE INDEX idx_matches_loser  ON matches (loser_id);
