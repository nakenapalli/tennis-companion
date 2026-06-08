"use client";

import { useParams } from "next/navigation";
import useSWR from "swr";
import Link from "next/link";
import { fetcher } from "@/lib/api";
import type { MatchDetail } from "@/lib/types";
import { MatchHeader } from "@/components/MatchHeader";
import { MatchChat } from "@/components/MatchChat";

export default function MatchPage() {
  const { externalId } = useParams<{ externalId: string }>();
  // Keep the score fresh while live; the chat manages its own live updates over SSE.
  const { data, error, isLoading } = useSWR<MatchDetail>(`/api/matches/${externalId}`, fetcher, { refreshInterval: 15000 });

  return (
    <div>
      <Link href="/scores" className="player-link">← Scores</Link>
      {isLoading ? (
        <div className="spinner">Loading…</div>
      ) : error || !data ? (
        <div className="empty">Match not found — it may have rolled off the recent list.</div>
      ) : (
        <>
          <MatchHeader m={data} />
          <MatchChat matchId={externalId} locked={data.status === "finished"} />
        </>
      )}
    </div>
  );
}
