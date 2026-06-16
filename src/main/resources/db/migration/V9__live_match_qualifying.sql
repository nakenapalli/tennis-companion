-- Qualifying-draw matches. api-tennis reuses main-draw round names ("Final", "Semi-finals") for the
-- qualifying draw and only distinguishes them via the event_qualification flag. Persist that flag so the
-- Postgres fallback orders matches the same way the Redis cache does — qualifying gets no round bonus and
-- ranks below the main draw (a qualifying final must not be weighted like the tournament final).
ALTER TABLE live_matches ADD COLUMN qualifying BOOLEAN NOT NULL DEFAULT FALSE;
