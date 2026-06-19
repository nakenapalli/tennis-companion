"use client";

import { Suspense } from "react";
import { useParams, useSearchParams } from "next/navigation";
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
          {/* A finished match's chat is locked and adds nothing, so it's hidden entirely — only show it live. */}
          {data.status !== "finished" && (
            // useSearchParams must sit under a Suspense boundary for the production build to prerender the page.
            <Suspense fallback={null}>
              <ChatSection matchId={externalId} />
            </Suspense>
          )}
        </>
      )}
    </div>
  );
}

/** Reads an optional `?thread=` deep-link (e.g. from a tournament's Threads tab) and opens it directly. */
function ChatSection({ matchId }: { matchId: string }) {
  const initialThreadId = useSearchParams().get("thread");
  // Only rendered for in-progress matches, so chat is never locked here.
  return <MatchChat matchId={matchId} locked={false} initialThreadId={initialThreadId} />;
}
