import Link from "next/link";
import type { LiveMatch, PlayerSide, SideScore } from "@/lib/types";
import { TierBadge } from "@/components/TierBadge";

/**
 * `grouped` = rendered under a per-tournament section on /scores: the tournament name + tier badge live
 * in the section header, so the card shows only status + round. Otherwise (home) the card carries the
 * tournament name, the detailed tier badge, and the round on the right.
 */
export function MatchCard({ m, grouped = false }: { m: LiveMatch; grouped?: boolean }) {
  const live = m.status === "live";
  // The feed encodes round as "WTA French Open - Final"; show just the round suffix.
  const round = m.round?.includes(" - ") ? m.round.split(" - ").pop()?.trim() : m.round;
  return (
    <article className="card">
      <div className="match-top">
        <span className={`badge ${live ? "live" : "final"}`}>{live ? "● Live" : "Final"}</span>
        <span className="match-meta">
          {!grouped && <TierBadge tier={m.tier} tour={m.tour} detailed />}
          {!grouped && m.tournamentName && <span className="tag">{m.tournamentName}</span>}
          {round && <span className="tag">{round}</span>}
        </span>
      </div>
      <PlayerLine side={m.player1} score={m.score?.home} />
      <PlayerLine side={m.player2} score={m.score?.away} />
    </article>
  );
}

function PlayerLine({ side, score }: { side: PlayerSide; score?: SideScore }) {
  const sets = score?.sets?.length ? score.sets.join("  ") : "";
  return (
    <div className="player-row">
      <span>
        {side.playerId ? (
          <Link href={`/players/${side.playerId}`} className="player-link">{side.name}</Link>
        ) : (
          side.name
        )}
        {side.country ? <span className="muted"> {side.country}</span> : null}
      </span>
      <span className="score-cell">
        {sets}
        {score?.point ? `  ${score.point}` : ""}
      </span>
    </div>
  );
}
