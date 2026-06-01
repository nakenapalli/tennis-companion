-- Phase 4: users & personalization (design §10, §12).
CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    email         TEXT UNIQUE NOT NULL,
    password_hash TEXT NOT NULL,            -- BCrypt
    is_admin      BOOLEAN NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE user_home_config (
    user_id BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    layout  JSONB NOT NULL                  -- ordered widget list + per-widget options
);

CREATE TABLE user_favorites (
    user_id   BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    player_id BIGINT NOT NULL,              -- soft ref to players(player_id)
    PRIMARY KEY (user_id, player_id)
);
CREATE INDEX idx_user_favorites_user ON user_favorites (user_id);
