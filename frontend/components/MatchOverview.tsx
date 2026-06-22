"use client";

import Link from "next/link";
import { roundSuffix } from "@/lib/score";
import type { MatchDetail } from "@/lib/types";

/** Condensed match facts as a single muted line under the score header. */
export function MatchOverview({ match }: { match: MatchDetail }) {
  const round = roundSuffix(match.round);
  const duration = matchDuration(match.startTime, match.endedAt);

  const items: React.ReactNode[] = [];
  if (match.tournamentName) {
    items.push(
      match.tournamentId ? (
        <Link href={`/tournaments/${match.tournamentId}`} className="player-link">{match.tournamentName}</Link>
      ) : (
        match.tournamentName
      ),
    );
  }
  if (round) items.push(`${round}${match.qualifying ? " (qualifying)" : ""}`);
  if (match.surface) items.push(match.surface);
  if (match.startTime) items.push(formatStart(match.startTime));
  if (duration) items.push(duration);

  if (items.length === 0) return null;
  return (
    <div className="muted" style={{ fontSize: 13, margin: "6px 0 4px" }}>
      {items.map((it, i) => (
        <span key={i}>
          {i > 0 && <span style={{ margin: "0 6px" }}>·</span>}
          {it}
        </span>
      ))}
    </div>
  );
}

function formatStart(iso: string): string {
  return new Date(iso).toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });
}

/** Elapsed between start and (approx) end, as "1h 47m". Null when not computable. */
function matchDuration(start?: string, end?: string): string | null {
  if (!start || !end) return null;
  const mins = Math.round((new Date(end).getTime() - new Date(start).getTime()) / 60000);
  if (mins <= 0 || mins > 600) return null; // guard against bad timestamps
  return `${Math.floor(mins / 60)}h ${mins % 60}m`;
}
