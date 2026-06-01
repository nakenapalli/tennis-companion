-- Flyway baseline for tennis-companion.
-- The real schema (players, matches, rankings_history, tournaments, live_matches, live_rankings,
-- entity_map, generated_insights, users, ...) lands in Phase 1+ (design doc §12).
-- This no-op establishes the Flyway history table so later migrations have a clean baseline.
SELECT 1;
