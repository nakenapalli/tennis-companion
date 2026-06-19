import type { LiveMatch } from "@/lib/types";
import { setWinners } from "@/lib/score";

/**
 * A condensed, non-interactive match score: two player rows with their set scores. Used on tournament
 * thread cards where the full clickable MatchCard would be too heavy.
 */
export function MatchScoreMini({ m }: { m: LiveMatch }) {
  const live = m.status === "live";
  const winners = setWinners(m.score, live);
  return (
    <div className="mini-score">
      <MiniRow name={m.player1.name} sets={m.score?.home?.sets ?? []} wonAt={(i) => winners[i] === "home"} />
      <MiniRow name={m.player2.name} sets={m.score?.away?.sets ?? []} wonAt={(i) => winners[i] === "away"} />
    </div>
  );
}

function MiniRow({ name, sets, wonAt }: { name: string; sets: number[]; wonAt: (i: number) => boolean }) {
  return (
    <div className="mini-row">
      <span className="mini-name">{name}</span>
      <span className="mini-sets">
        {sets.map((g, i) => (
          <span key={i} className={wonAt(i) ? "won" : undefined}>{g}</span>
        ))}
      </span>
    </div>
  );
}
