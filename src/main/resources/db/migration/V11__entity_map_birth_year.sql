-- V11: persist the enriched upstream player profile on the review queue.
--
-- The live-scores feed carries no country/rank/birth year, so most review-queue rows have null
-- country_code/rank_hint (added in V7). The admin review card fetches the player's profile live from
-- the provider (get_players) to disambiguate namesakes; this column lets that fetched profile be
-- persisted alongside country_code/rank_hint, so repeat reviews read it for free and the offline
-- Tier-3 classifier gains a birth-year signal. Nullable — the provider may supply none of it.

ALTER TABLE entity_map ADD COLUMN birth_year INT;
