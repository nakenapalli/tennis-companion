-- Circuit category for live/recent matches (ATP, WTA, Challenger, ITF, Junior, ...) so the UI can
-- filter to main tour (ATP & WTA) by default while the backend still ingests everything. Derived from
-- the upstream event_type_type at poll time; null for any rows written before this column existed.
ALTER TABLE live_matches ADD COLUMN category TEXT;
