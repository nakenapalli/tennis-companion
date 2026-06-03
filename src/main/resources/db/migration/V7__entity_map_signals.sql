-- Phase 6b: Tier-3 LLM reconciliation runs as an OFFLINE batch over the review queue, so for each
-- unresolved row it must re-derive the candidate set on its own (the live request's signals are gone
-- by then). Persist those signals on the row. `tour` is required to pick the ATP vs WTA candidate
-- pool; country/rank are extra evidence the classifier weighs. All nullable — older rows predate this.
ALTER TABLE entity_map
    ADD COLUMN tour         TEXT,
    ADD COLUMN country_code TEXT,
    ADD COLUMN rank_hint    INT;
