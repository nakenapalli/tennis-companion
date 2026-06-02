-- AI-generated content (the weekly "What's Worth Watching" digest). One row per generation; stored
-- as DRAFT with the exact fact sheet used (source_data) + model id for traceability. The serving API
-- only returns PUBLISHED rows; publishing is a manual admin step (design §9).
CREATE TABLE generated_insights (
    id            BIGSERIAL PRIMARY KEY,
    type          TEXT NOT NULL,                 -- 'weekly_digest' (extensible)
    title         TEXT NOT NULL,
    body_markdown TEXT NOT NULL,
    source_data   JSONB NOT NULL,                -- the fact sheet the model was grounded on
    model         TEXT,
    status        TEXT NOT NULL DEFAULT 'DRAFT', -- DRAFT | PUBLISHED
    generated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ
);

CREATE INDEX idx_insights_type_status ON generated_insights (type, status, published_at DESC);
