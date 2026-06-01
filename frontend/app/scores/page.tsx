"use client";

import useSWR from "swr";
import { fetcher } from "@/lib/api";
import type { LiveMatch } from "@/lib/types";
import { MatchCard } from "@/components/MatchCard";

export default function ScoresPage() {
  const live = useSWR<LiveMatch[]>("/api/scores/live", fetcher, { refreshInterval: 30000 });
  const recent = useSWR<LiveMatch[]>("/api/scores/recent?days=3", fetcher);

  return (
    <div>
      <h1>Scores</h1>
      <p className="sub">Live matches refresh automatically.</p>

      <h2>Live now</h2>
      <Grid data={live.data} loading={live.isLoading} empty="No live matches right now." />

      <h2>Recently finished</h2>
      <Grid data={recent.data} loading={recent.isLoading} empty="Nothing recent to show." />
    </div>
  );
}

function Grid({ data, loading, empty }: { data?: LiveMatch[]; loading: boolean; empty: string }) {
  if (loading) return <div className="spinner">Loading…</div>;
  if (!data || data.length === 0) return <div className="empty">{empty}</div>;
  return (
    <div className="grid">
      {data.map((m) => (
        <MatchCard key={m.externalId} m={m} />
      ))}
    </div>
  );
}
