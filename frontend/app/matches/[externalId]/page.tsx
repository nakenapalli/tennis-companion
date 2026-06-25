"use client";

import { Suspense, useMemo, type ReactNode } from "react";
import { useParams, useSearchParams } from "next/navigation";
import useSWR from "swr";
import Link from "next/link";
import { fetcher } from "@/lib/api";
import { pickPlayerColors, type PlayerColors } from "@/lib/playerColors";
import type { MatchDetail } from "@/lib/types";
import { MatchHeader } from "@/components/MatchHeader";
import { MatchChat } from "@/components/MatchChat";
import { MatchMomentum } from "@/components/MatchMomentum";
import { MatchStats } from "@/components/MatchStats";
import { MatchOverview } from "@/components/MatchOverview";
import { MatchH2H } from "@/components/MatchH2H";
import { MatchPlayers } from "@/components/MatchPlayers";

export default function MatchPage() {
  const { externalId } = useParams<{ externalId: string }>();
  // Keep the score fresh while live; the chat manages its own live updates over SSE.
  const { data, error, isLoading } = useSWR<MatchDetail>(`/api/matches/${externalId}`, fetcher, { refreshInterval: 15000 });

  // Assign each player a flag-derived color ONCE per match load, here at the page level, so every section
  // shares the same two colors. Memoized on the countries so it's stable across live refreshes, but
  // re-rolls on a fresh page load.
  const colors = useMemo(
    () => pickPlayerColors(data?.player1?.country, data?.player2?.country),
    [externalId, data?.player1?.country, data?.player2?.country],
  );

  return (
    <div>
      {/* useSearchParams must sit under a Suspense boundary for the production build to prerender. */}
      <Suspense fallback={<Link href="/scores" className="player-link">← Scores</Link>}>
        <BackLink />
      </Suspense>
      {isLoading ? (
        <div className="spinner">Loading…</div>
      ) : error || !data ? (
        <div className="empty">Match not found — it may have rolled off the recent list.</div>
      ) : (
        <>
          <MatchHeader m={data} />
          <MatchOverview match={data} />
          {/* useSearchParams must sit under a Suspense boundary for the production build to prerender. */}
          <Suspense fallback={null}>
            <MatchSections externalId={externalId} match={data} colors={colors} />
          </Suspense>
        </>
      )}
    </div>
  );
}

/**
 * Dynamic back button: returns to wherever the user came from (Scores / a tournament / a filtered
 * tournament), carried in the `b` (href) + `bl` (label) params by `matchHref`. Defaults to Scores. The
 * href is only honored if it's an internal path, so a crafted param can't turn this into an open redirect.
 */
function BackLink() {
  const sp = useSearchParams();
  const raw = sp.get("b");
  const href = raw && raw.startsWith("/") ? raw : "/scores";
  const label = sp.get("bl") || "Scores";
  return (
    <Link href={href} className="player-link">
      ← {label}
    </Link>
  );
}

/** All match-detail sections stacked one after another (no tabs). */
function MatchSections({ externalId, match, colors }: { externalId: string; match: MatchDetail; colors: PlayerColors }) {
  const threadParam = useSearchParams().get("thread"); // deep-link from a tournament's Threads tab
  const live = match.status === "live";

  return (
    <>
      <Section title="Momentum">
        <MatchMomentum externalId={externalId} live={live} colors={colors} />
      </Section>
      <Section title="Stats">
        <MatchStats externalId={externalId} live={live} colors={colors} />
      </Section>
      <Section title="Players">
        <MatchPlayers externalId={externalId} live={live} colors={colors} />
      </Section>
      <Section title="Head-to-head">
        <MatchH2H externalId={externalId} live={live} colors={colors} />
      </Section>
      {match.status !== "finished" && (
        <Section title="Discussion">
          <MatchChat matchId={externalId} locked={false} initialThreadId={threadParam} />
        </Section>
      )}
    </>
  );
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section style={{ marginTop: 32 }}>
      <h2 style={{ margin: "0 0 14px" }}>{title}</h2>
      {children}
    </section>
  );
}
