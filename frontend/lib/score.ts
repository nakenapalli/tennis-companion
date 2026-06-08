import type { LiveMatch } from "./types";

/**
 * Per-set winner ("home"/"away"/null). A set is decided once it's no longer the current set OR its games
 * show it's complete — so a set won mid-match colors immediately, while a genuine in-progress set
 * (0-0, 3-2, 6-6) stays undecided. Shared by the score card and the match header.
 */
export function setWinners(score: LiveMatch["score"], live: boolean): Array<"home" | "away" | null> {
  const home = score?.home?.sets ?? [];
  const away = score?.away?.sets ?? [];
  const n = Math.max(home.length, away.length);
  const out: Array<"home" | "away" | null> = [];
  for (let i = 0; i < n; i++) {
    const h = home[i];
    const a = away[i];
    if (typeof h !== "number" || typeof a !== "number" || h === a) {
      out.push(null);
      continue;
    }
    const decided = !live || i < n - 1 || setComplete(h, a);
    out.push(decided ? (h > a ? "home" : "away") : null);
  }
  return out;
}

/** A standard set is complete at 6+ games with a 2-game margin, or at 7 (covers 7-5 and 7-6 tiebreaks). */
function setComplete(h: number, a: number): boolean {
  const max = Math.max(h, a);
  const min = Math.min(h, a);
  return max >= 6 && (max - min >= 2 || max === 7);
}

/** The feed encodes round as "WTA French Open - Final"; show just the round suffix. */
export function roundSuffix(round?: string): string | undefined {
  return round?.includes(" - ") ? round.split(" - ").pop()?.trim() : round;
}
