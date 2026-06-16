"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import type { MatchDetail, PlayerSide, SideScore } from "@/lib/types";
import { Flag } from "@/components/Flag";
import { TierBadge } from "@/components/TierBadge";
import { roundSuffix, setWinners } from "@/lib/score";

/** The detailed score card at the top of the match view: flags, rank, set/point scores, serve, and an
 *  elapsed (live, ticking) / approximate-duration (finished) readout. */
export function MatchHeader({ m }: { m: MatchDetail }) {
  const live = m.status === "live";
  const serve = live ? m.serve : undefined;
  const round = roundSuffix(m.round);
  const winners = setWinners(m.score, live);
  const elapsed = useElapsed(m);

  return (
    <section className="card match-header">
      <div className="match-top">
        <span className={`badge ${live ? "live" : "final"}`}>{live ? "● Live" : "Final"}</span>
        <span className="match-meta">
          {m.tournamentName && <span className="tag">{m.tournamentName}</span>}
          <TierBadge tier={m.tier} tour={m.tour} detailed />
          {m.qualifying && <span className="tag tag-qual">Qualifying</span>}
          {round && <span className="tag">{round}</span>}
          {elapsed && <span className="tag">⏱ {elapsed}</span>}
        </span>
      </div>
      <HeaderPlayerLine side={m.player1} score={m.score?.home} live={live} serving={serve === "home"} wonSets={winners.map((w) => w === "home")} />
      <HeaderPlayerLine side={m.player2} score={m.score?.away} live={live} serving={serve === "away"} wonSets={winners.map((w) => w === "away")} />
    </section>
  );
}

function HeaderPlayerLine({
  side,
  score,
  live,
  serving,
  wonSets,
}: {
  side: PlayerSide;
  score?: SideScore;
  live: boolean;
  serving: boolean;
  wonSets: boolean[];
}) {
  const sets = score?.sets ?? [];
  return (
    <div className="player-row header-row">
      <span className="player-name">
        {live && <span className={serving ? "serve-dot" : "serve-dot hidden"} aria-label={serving ? "Serving" : undefined} />}
        <Flag ioc={side.country} />
        {side.playerId ? (
          <Link href={`/players/${side.playerId}`} className="player-link">{side.name}</Link>
        ) : (
          side.name
        )}
        {side.rank != null && <span className="player-rank">{side.rank}</span>}
      </span>
      <span className="score-cell">
        {sets.length > 0 && (
          <span className="set-scores">
            {sets.map((g, i) => (
              <span key={i} className={`set-game${wonSets[i] ? " won" : ""}`}>{g}</span>
            ))}
          </span>
        )}
        {live && <span className="game-point">{score?.point ?? ""}</span>}
      </span>
    </div>
  );
}

/** Live: ticking now − start (H:MM:SS). Finished: ≈ endedAt − start. Approximate — start is scheduled. */
function useElapsed(m: MatchDetail): string | null {
  const live = m.status === "live";
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!live) return;
    const id = setInterval(() => setNow(Date.now()), 30_000); // hours/minutes only — no need to tick every second
    return () => clearInterval(id);
  }, [live]);

  if (!m.startTime) return null;
  const start = new Date(m.startTime).getTime();
  if (live) return format(now - start);
  if (m.endedAt) return `≈ ${format(new Date(m.endedAt).getTime() - start)}`;
  return null;
}

/** Hours and minutes only, e.g. "2h 14m" or "14m". */
function format(ms: number): string {
  if (ms < 0) return "—";
  const totalMin = Math.floor(ms / 60_000);
  const h = Math.floor(totalMin / 60);
  const mn = totalMin % 60;
  return h > 0 ? `${h}h ${mn}m` : `${mn}m`;
}
