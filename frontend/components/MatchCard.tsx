"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import type { LiveMatch, PlayerSide, SideScore } from "@/lib/types";
import { Flag } from "@/components/Flag";
import { TierBadge } from "@/components/TierBadge";
import { roundSuffix, setWinners } from "@/lib/score";

/**
 * `grouped` = rendered under a per-tournament section on /scores: the tournament name + tier badge live in
 * the section header, so the card shows only status + round. The whole card navigates to the match view;
 * inner player links stop propagation so they still go to the player page.
 */
export function MatchCard({ m, grouped = false }: { m: LiveMatch; grouped?: boolean }) {
  const router = useRouter();
  const live = m.status === "live";
  const serve = live ? m.serve : undefined; // "home" | "away"
  const round = roundSuffix(m.round);
  const winners = setWinners(m.score, live);
  return (
    <article
      className="card card-link card-clickable"
      role="link"
      tabIndex={0}
      onClick={() => router.push(`/matches/${m.externalId}`)}
      onKeyDown={(e) => {
        if (e.key === "Enter") router.push(`/matches/${m.externalId}`);
      }}
    >
      <div className="match-top">
        <span className={`badge ${live ? "live" : "final"}`}>{live ? "● Live" : "Completed"}</span>
        <span className="match-meta">
          {!grouped && m.tournamentName &&
            (m.tournamentId != null ? (
              <Link href={`/tournaments/${m.tournamentId}`} className="tag tag-link" onClick={(e) => e.stopPropagation()}>
                {m.tournamentName}
              </Link>
            ) : (
              <span className="tag">{m.tournamentName}</span>
            ))}
          {!grouped && <TierBadge tier={m.tier} tour={m.tour} detailed />}
          {m.qualifying && <span className="tag tag-qual">Qualifying</span>}
          {round && <span className="tag">{round}</span>}
        </span>
      </div>
      <PlayerLine side={m.player1} score={m.score?.home} live={live} serving={serve === "home"} wonSets={winners.map((w) => w === "home")} />
      <PlayerLine side={m.player2} score={m.score?.away} live={live} serving={serve === "away"} wonSets={winners.map((w) => w === "away")} />
    </article>
  );
}

function PlayerLine({
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
    <div className="player-row">
      <span className="player-name">
        {live && <span className={serving ? "serve-dot" : "serve-dot hidden"} aria-label={serving ? "Serving" : undefined} />}
        <Flag ioc={side.country} />
        {side.playerId ? (
          <Link href={`/players/${side.playerId}`} className="player-link" onClick={(e) => e.stopPropagation()}>
            {side.name}
          </Link>
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
