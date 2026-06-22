-- One-time cleanup: merge duplicate player rows that the Sackmann CSVs introduced — the same person
-- under multiple player_ids (hence multiple UUIDs). "Same person" = identical first_name + last_name +
-- tour + birth_date (high confidence). For each group we keep one canonical UUID, repoint all references
-- to it, and delete the redundant rows. Transactional: all-or-nothing.
--
-- Not a Flyway migration by design (the Sackmann source is no longer public, so there's no re-load to
-- guard against). Run once against the dev DB:
--   docker exec -i tc-postgres psql -U tennis -d tennis < scripts/dedupe-players.sql

BEGIN;

-- Map each redundant UUID → the canonical UUID for its group.
CREATE TEMP TABLE dup_merge ON COMMIT DROP AS
WITH grp AS (
  SELECT p.id, p.first_name, p.last_name, p.tour, p.birth_date, p.sackmann_id,
    (SELECT count(*) FROM matches m
       WHERE m.winner_id = p.id OR m.loser_id = p.id OR m.player1_id = p.id OR m.player2_id = p.id)
      + (SELECT count(*) FROM rankings r WHERE r.player_id = p.id) AS refs,
    ((p.country_code IS NOT NULL)::int + (p.hand IS NOT NULL)::int + (p.height_cm IS NOT NULL)::int) AS completeness
  FROM players p
  JOIN (SELECT first_name, last_name, tour, birth_date
        FROM players WHERE birth_date IS NOT NULL
        GROUP BY 1, 2, 3, 4 HAVING count(*) > 1) g
    USING (first_name, last_name, tour, birth_date)
  WHERE p.birth_date IS NOT NULL
),
ranked AS (
  SELECT *, row_number() OVER (
    PARTITION BY first_name, last_name, tour, birth_date
    ORDER BY refs DESC, completeness DESC, sackmann_id ASC) AS rn
  FROM grp
)
SELECT r.id AS dup_id, c.id AS canonical_id
FROM ranked r
JOIN ranked c
  ON c.first_name = r.first_name AND c.last_name = r.last_name
 AND c.tour = r.tour AND c.birth_date = r.birth_date AND c.rn = 1
WHERE r.rn > 1;

-- Repoint matches (no FK on these columns; we update before deleting so nothing is orphaned).
UPDATE matches SET winner_id  = dm.canonical_id FROM dup_merge dm WHERE matches.winner_id  = dm.dup_id;
UPDATE matches SET loser_id   = dm.canonical_id FROM dup_merge dm WHERE matches.loser_id   = dm.dup_id;
UPDATE matches SET player1_id = dm.canonical_id FROM dup_merge dm WHERE matches.player1_id = dm.dup_id;
UPDATE matches SET player2_id = dm.canonical_id FROM dup_merge dm WHERE matches.player2_id = dm.dup_id;

-- Rankings: drop redundant rows that would collide with the canonical's existing row, then repoint the rest.
DELETE FROM rankings r USING dup_merge dm
 WHERE r.player_id = dm.dup_id
   AND EXISTS (SELECT 1 FROM rankings r2
                WHERE r2.player_id = dm.canonical_id
                  AND r2.source = r.source AND r2.ranking_date = r.ranking_date AND r2.tour = r.tour);
UPDATE rankings SET player_id = dm.canonical_id FROM dup_merge dm WHERE rankings.player_id = dm.dup_id;

-- Confirmed mappings (none expected, but safe).
UPDATE entity_map SET player_id = dm.canonical_id FROM dup_merge dm WHERE entity_map.player_id = dm.dup_id;

-- Favorites: drop colliding (user already favorited the canonical), then repoint the rest.
DELETE FROM user_favorites uf USING dup_merge dm
 WHERE uf.player_id = dm.dup_id
   AND EXISTS (SELECT 1 FROM user_favorites uf2
                WHERE uf2.user_id = uf.user_id AND uf2.player_id = dm.canonical_id);
UPDATE user_favorites SET player_id = dm.canonical_id FROM dup_merge dm WHERE user_favorites.player_id = dm.dup_id;

-- Finally, remove the redundant player rows.
DELETE FROM players WHERE id IN (SELECT dup_id FROM dup_merge);

COMMIT;
