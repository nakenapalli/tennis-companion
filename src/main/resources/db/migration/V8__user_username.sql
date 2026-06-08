-- Phase 7: usernames (shown as the chat handle). Backfill existing users from their email local-part,
-- deduping on collision, then enforce case-insensitive uniqueness. New registrations require a username.
ALTER TABLE users ADD COLUMN username TEXT;

UPDATE users SET username = split_part(email, '@', 1) WHERE username IS NULL;

-- Disambiguate any collisions produced by the backfill (e.g. two "alex" local-parts).
UPDATE users u SET username = u.username || '_' || u.id
WHERE EXISTS (SELECT 1 FROM users u2 WHERE lower(u2.username) = lower(u.username) AND u2.id <> u.id);

CREATE UNIQUE INDEX idx_users_username ON users (lower(username));
