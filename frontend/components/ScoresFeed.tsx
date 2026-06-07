"use client";

import { useMemo } from "react";
import useSWR from "swr";
import Link from "next/link";
import { fetcher } from "@/lib/api";
import { isMainTour, usePrefs } from "@/lib/prefs";
import type { LiveMatch, Tournament } from "@/lib/types";
import { MatchCard } from "@/components/MatchCard";
import { TierBadge } from "@/components/TierBadge";

/** Fallback when a tournament isn't in /current: which tours its matches span. */
function tourLabel(matches: LiveMatch[]): string | null {
  const tours = new Set(matches.map((m) => m.tour).filter(Boolean));
  if (tours.has("ATP") && tours.has("WTA")) return "ATP & WTA";
  if (tours.has("ATP")) return "ATP";
  if (tours.has("WTA")) return "WTA";
  return null;
}

/**
 * Shows live matches when play is on; otherwise falls back to today's completed matches. `limit` caps the
 * cards (home dashboard) and `moreHref` adds a link to the full scores page. `groupByTournament` (the
 * /scores page) renders one section per tournament instead of a single grid — sections follow the
 * server's importance order, so a Grand Slam leads.
 */
export function ScoresFeed({
  limit,
  moreHref,
  groupByTournament,
}: {
  limit?: number;
  moreHref?: string;
  groupByTournament?: boolean;
}) {
  const { mainTourOnly } = usePrefs();
  const live = useSWR<LiveMatch[]>("/api/scores/live", fetcher, { refreshInterval: 30000 });
  const recent = useSWR<LiveMatch[]>("/api/scores/recent", fetcher, { refreshInterval: 60000 });
  // Tournament `level` for the section's tour badge (only fetched when grouping by tournament).
  const tournaments = useSWR<Tournament[]>(groupByTournament ? "/api/tournaments/current" : null, fetcher);
  const levelByName = useMemo(() => {
    const map = new Map<string, string>();
    for (const t of tournaments.data ?? []) if (t.level) map.set(t.name.trim().toLowerCase(), t.level);
    return map;
  }, [tournaments.data]);

  // Apply the main-tour filter before deciding live-vs-recent, so "no main-tour live" still falls back.
  const filt = (arr?: LiveMatch[]) => (arr ?? []).filter((m) => !mainTourOnly || isMainTour(m.category));
  const liveMatches = filt(live.data);
  const recentMatches = filt(recent.data);

  const hasLive = liveMatches.length > 0;
  const source = hasLive ? liveMatches : recentMatches;
  const matches = limit ? source.slice(0, limit) : source;
  const title = hasLive ? "Live now" : "Recently completed";
  const loading = live.isLoading || (!hasLive && recent.isLoading);

  if (loading) {
    return (
      <section>
        <h2>{title}</h2>
        <div className="spinner">Loading…</div>
      </section>
    );
  }
  if (matches.length === 0) {
    return (
      <section>
        <h2>{title}</h2>
        <div className="empty">No recent matches to show.</div>
      </section>
    );
  }

  if (groupByTournament) {
    const groups = new Map<string, LiveMatch[]>();
    for (const m of matches) {
      const key = m.tournamentName ?? "Other";
      const arr = groups.get(key);
      if (arr) arr.push(m);
      else groups.set(key, [m]);
    }
    return (
      <div>
        {[...groups.entries()].map(([name, ms]) => {
          const level = levelByName.get(name.trim().toLowerCase()) ?? tourLabel(ms);
          return (
            <section key={name} className="tourney-group">
              <div className="tourney-head">
                <h2>{name}</h2>
                {level && <span className="tour-badge">{level}</span>}
                <TierBadge tier={ms[0].tier} />
              </div>
              <div className="grid">
                {ms.map((m) => (
                  <MatchCard key={m.externalId} m={m} grouped />
                ))}
              </div>
            </section>
          );
        })}
      </div>
    );
  }

  return (
    <section>
      <div className="row-between">
        <h2>{title}</h2>
        {moreHref && (
          <Link href={moreHref} className="player-link">
            All scores →
          </Link>
        )}
      </div>
      <div className="grid">
        {matches.map((m) => (
          <MatchCard key={m.externalId} m={m} />
        ))}
      </div>
    </section>
  );
}
