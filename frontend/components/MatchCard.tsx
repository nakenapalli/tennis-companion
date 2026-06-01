import Link from "next/link";
import type { LiveMatch, PlayerSide, SideScore } from "@/lib/types";

export function MatchCard({ m }: { m: LiveMatch }) {
  const live = m.status === "live";
  const context = [m.tournamentName, m.round].filter(Boolean).join(" · ");
  return (
    <article className="card">
      <div className="match-top">
        <span className={`badge ${live ? "live" : "final"}`}>{live ? "● Live" : "Final"}</span>
        {context && <span className="tag">{context}</span>}
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
