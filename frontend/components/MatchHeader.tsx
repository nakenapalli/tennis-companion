"use client";

import Link from "next/link";
import type { MatchDetail, PlayerSide, SideScore } from "@/lib/types";
import { Flag } from "@/components/Flag";
import { TierBadge } from "@/components/TierBadge";
import { roundSuffix, setWinners } from "@/lib/score";

/** The detailed score card at the top of the match view: flags, rank, set/point scores, and serve. */
export function MatchHeader({ m }: { m: MatchDetail }) {
  const live = m.status === "live";
  const serve = live ? m.serve : undefined;
  const round = roundSuffix(m.round);
  const winners = setWinners(m.score, live);

  return (
    <section className="card match-header">
      <div className="match-top">
        <span className={`badge ${live ? "live" : "final"}`}>{live ? "● Live" : "Final"}</span>
        <span className="match-meta">
          {m.tournamentName && <span className="tag">{m.tournamentName}</span>}
          <TierBadge tier={m.tier} tour={m.tour} detailed />
          {m.qualifying && <span className="tag tag-qual">Qualifying</span>}
          {round && <span className="tag">{round}</span>}
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
