"use client";

import useSWR from "swr";
import Link from "next/link";
import { fetcher } from "@/lib/api";
import { isMainTour, usePrefs } from "@/lib/prefs";
import type { LiveMatch } from "@/lib/types";
import { MatchCard } from "@/components/MatchCard";

/**
 * Shows live matches when play is on; otherwise falls back to today's completed matches. One section,
 * conditional content — so the page never shows an empty "Live now" grid. `limit` caps the cards
 * (home dashboard); `moreHref` adds a link to the full scores page.
 */
export function ScoresFeed({ limit, moreHref }: { limit?: number; moreHref?: string }) {
  const { mainTourOnly } = usePrefs();
  const live = useSWR<LiveMatch[]>("/api/scores/live", fetcher, { refreshInterval: 30000 });
  const recent = useSWR<LiveMatch[]>("/api/scores/recent", fetcher, { refreshInterval: 60000 });

  // Apply the main-tour filter before deciding live-vs-recent, so "no main-tour live" still falls back.
  const filt = (arr?: LiveMatch[]) =>
    (arr ?? []).filter((m) => !mainTourOnly || isMainTour(m.category));
  const liveMatches = filt(live.data);
  const recentMatches = filt(recent.data);

  const hasLive = liveMatches.length > 0;
  const source = hasLive ? liveMatches : recentMatches;
  const matches = limit ? source.slice(0, limit) : source;
  const title = hasLive ? "Live now" : "Recently completed";
  const loading = live.isLoading || (!hasLive && recent.isLoading);

  return (
    <section>
      <div className="row-between">
        <h2>{title}</h2>
        {moreHref && <Link href={moreHref} className="player-link">All scores →</Link>}
      </div>
      {loading ? (
        <div className="spinner">Loading…</div>
      ) : matches.length > 0 ? (
        <div className="grid">
          {matches.map((m) => (
            <MatchCard key={m.externalId} m={m} />
          ))}
        </div>
      ) : (
        <div className="empty">No recent matches to show.</div>
      )}
    </section>
  );
}
