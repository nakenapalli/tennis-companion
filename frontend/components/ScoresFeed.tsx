"use client";

import { useMemo } from "react";
import useSWR from "swr";
import Link from "next/link";
import { fetcher } from "@/lib/api";
import { isMainTour, usePrefs } from "@/lib/prefs";
import type { BackContext } from "@/lib/matchHref";
import type { LiveMatch, Tournament } from "@/lib/types";
import { MatchCard } from "@/components/MatchCard";
import { MatchGrid } from "@/components/MatchGrid";
import { TierBadge } from "@/components/TierBadge";

const COLUMN_LIMIT = 4; // single column × 4 rows per tour on the home dashboard

/** Fallback when a tournament isn't in /current: which tours its matches span. */
function tourLabel(matches: LiveMatch[]): string | null {
  const tours = new Set(matches.map((m) => m.tour).filter(Boolean));
  if (tours.has("ATP") && tours.has("WTA")) return "ATP & WTA";
  if (tours.has("ATP")) return "ATP";
  if (tours.has("WTA")) return "WTA";
  return null;
}

/** Split a tournament's matches into ATP / WTA / Other sections (only the tours actually present). */
function splitByTour(matches: LiveMatch[]) {
  return [
    { tour: "ATP" as const, matches: matches.filter((m) => m.tour === "ATP") },
    { tour: "WTA" as const, matches: matches.filter((m) => m.tour === "WTA") },
    { tour: null, matches: matches.filter((m) => m.tour !== "ATP" && m.tour !== "WTA") },
  ].filter((s) => s.matches.length > 0);
}

/**
 * Shows live matches when play is on; otherwise falls back to today's completed matches. `limit` caps the
 * cards and `moreHref` adds a link to the full scores page. `groupByTournament` (the /scores page) renders
 * one section per tournament — and within each, ATP/WTA sub-sections capped at 6 with a "See more" into the
 * filtered tournament page. `tourColumns` (the home dashboard) renders ATP and WTA side by side, each a
 * 2×4 grid. `back` records this page as the origin so a card's match view can return here.
 */
export function ScoresFeed({
  limit,
  moreHref,
  groupByTournament,
  tourColumns,
  back,
}: {
  limit?: number;
  moreHref?: string;
  groupByTournament?: boolean;
  tourColumns?: boolean;
  back?: BackContext;
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
  const title = hasLive ? "Live now" : "Recent Matches";
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

  // Home dashboard: ATP | WTA side by side, each a 2×4 grid.
  if (tourColumns) {
    const cols = splitByTour(matches).filter((s) => s.tour !== null);
    return (
      <section>
        <div className="row-between">
          <h2 className="home-scores-title">{title}</h2>
          {moreHref && (
            <Link href={moreHref} className="player-link">
              All scores →
            </Link>
          )}
        </div>
        <div className="scores-tour-cols">
          {cols.map((s) => (
            <div key={s.tour}>
              <div className="tour-subhead">{s.tour}</div>
              <div className="grid grid-1col">
                {s.matches.slice(0, COLUMN_LIMIT).map((m) => (
                  <MatchCard key={m.externalId} m={m} back={back} />
                ))}
              </div>
            </div>
          ))}
        </div>
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
          const sections = splitByTour(ms);
          return (
            <section key={name} className="tourney-group">
              <div className="tourney-head">
                <h2>{name}</h2>
                {level && <span className="tour-badge">{level}</span>}
                <TierBadge tier={ms[0].tier} />
              </div>
              {sections.map((s) => {
                // "See more" → the filtered tournament page (all of that tour's matches for this event).
                const tid = s.matches.find((m) => m.tournamentId != null)?.tournamentId;
                const seeMore =
                  tid != null ? (s.tour ? `/tournaments/${tid}?tour=${s.tour}` : `/tournaments/${tid}`) : null;
                return (
                  <div key={s.tour ?? "other"} className="tour-section">
                    {sections.length > 1 && s.tour && <div className="tour-subhead">{s.tour}</div>}
                    <MatchGrid matches={s.matches} grouped back={back} seeMoreHref={seeMore} seeMoreAlways />
                  </div>
                );
              })}
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
          <MatchCard key={m.externalId} m={m} back={back} />
        ))}
      </div>
    </section>
  );
}
