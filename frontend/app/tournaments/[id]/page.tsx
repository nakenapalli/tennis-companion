"use client";

import { Suspense, useState } from "react";
import { useParams, useRouter, useSearchParams } from "next/navigation";
import Link from "next/link";
import useSWR from "swr";
import { fetcher } from "@/lib/api";
import { matchHref, type BackContext } from "@/lib/matchHref";
import type { Headline, LiveMatch, Tournament, TournamentThread } from "@/lib/types";
import { MatchCard } from "@/components/MatchCard";
import { MatchGrid } from "@/components/MatchGrid";
import { MatchScoreMini } from "@/components/MatchScoreMini";

type Tab = "overview" | "threads";

export default function TournamentPage() {
  const { id } = useParams<{ id: string }>();
  const [tab, setTab] = useState<Tab>("overview");
  const { data: t, error, isLoading } = useSWR<Tournament>(`/api/tournaments/${id}`, fetcher);
  const threads = useSWR<TournamentThread[]>(`/api/tournaments/${id}/threads`, fetcher, { refreshInterval: 20000 });

  return (
    <div>
      <Link href="/tournaments" className="player-link">← Tournaments</Link>
      {isLoading ? (
        <div className="spinner">Loading…</div>
      ) : error || !t ? (
        <div className="empty">Tournament not found.</div>
      ) : (
        <>
          <header className="tourney-hero">
            <div className="tourney-head">
              <h1>{t.name}</h1>
              {t.tour && <span className="tour-badge">{t.tour}</span>}
            </div>
            <div className="meta">
              {t.location && <span>{t.location}</span>}
              {t.surface && <span>{t.surface}</span>}
              {(t.startDate || t.endDate) && (
                <span>{t.startDate ?? ""}{t.endDate ? ` → ${t.endDate}` : ""}</span>
              )}
            </div>
          </header>

          <nav className="tabs">
            <button type="button" className={`tab${tab === "overview" ? " active" : ""}`} onClick={() => setTab("overview")}>
              Matches
            </button>
            <button type="button" className={`tab${tab === "threads" ? " active" : ""}`} onClick={() => setTab("threads")}>
              Threads
              {threads.data && threads.data.length > 0 && <span className="count">{threads.data.length}</span>}
            </button>
          </nav>

          {tab === "overview" ? (
            // useSearchParams (the ?tour= filter) must sit under a Suspense boundary to prerender.
            <Suspense fallback={<div className="spinner">Loading…</div>}>
              <Overview id={id} name={t.name} />
            </Suspense>
          ) : (
            <Threads threads={threads.data} loading={threads.isLoading} tournamentName={t.name} id={id} />
          )}
        </>
      )}
    </div>
  );
}

function Overview({ id, name }: { id: string; name: string }) {
  // `?tour=ATP|WTA` filters the page down to one tour's full match list (the "See more" target).
  const tourFilter = useSearchParams().get("tour");
  const { data: matches, isLoading } = useSWR<LiveMatch[]>(`/api/tournaments/${id}/matches`, fetcher, { refreshInterval: 30000 });
  const headlines = useSWR<Headline[]>(`/api/tournaments/${id}/headlines`, fetcher);

  const all = matches ?? [];
  // Split into ATP / WTA / Other sections (only the tours present). A single-tour event still renders as
  // one section but with the redundant tour header hidden (see `showHeader` below).
  const groups = [
    { label: "ATP", tour: "ATP" as const, matches: all.filter((m) => m.tour === "ATP") },
    { label: "WTA", tour: "WTA" as const, matches: all.filter((m) => m.tour === "WTA") },
    { label: "Other", tour: null, matches: all.filter((m) => m.tour !== "ATP" && m.tour !== "WTA") },
  ].filter((g) => g.matches.length > 0);

  // Filtered view: one tour's complete list (no cap), with a way back to the full event.
  const filtered = tourFilter ? groups.find((g) => g.tour === tourFilter) : undefined;

  return (
    <div>
      {isLoading ? (
        <div className="spinner">Loading…</div>
      ) : all.length === 0 ? (
        <div className="empty">No recent or live matches for this tournament right now.</div>
      ) : filtered ? (
        <section className="tourney-group">
          <div className="tourney-head">
            <h2>{filtered.label}</h2>
            <span className="tour-badge">{filtered.matches.length}</span>
            <Link href={`/tournaments/${id}`} className="player-link" style={{ fontSize: 13 }}>
              ← All matches
            </Link>
          </div>
          <div className="grid">
            {filtered.matches.map((m) => (
              <MatchCard
                key={m.externalId}
                m={m}
                grouped
                back={{ label: `${name} - ${filtered.tour}`, href: `/tournaments/${id}?tour=${filtered.tour}` }}
              />
            ))}
          </div>
        </section>
      ) : (
        // Cap each section at 6 with a "See more" into the filtered view. The tour header is dropped for a
        // single-tour event (the hero already shows the tour) but kept when ATP/WTA coexist.
        groups.map((g) => (
          <MatchGroup
            key={g.label}
            label={g.label}
            showHeader={groups.length > 1}
            matches={g.matches}
            seeMore={g.tour ? `/tournaments/${id}?tour=${g.tour}` : null}
            back={{ label: name, href: `/tournaments/${id}` }}
          />
        ))
      )}

      <h2>Headlines</h2>
      {!headlines.data ? (
        <div className="spinner">Loading…</div>
      ) : headlines.data.length === 0 ? (
        <div className="empty">No recent headlines for this tournament.</div>
      ) : (
        <div className="headlines">
          {headlines.data.map((h) => (
            <a key={h.url} href={h.url} target="_blank" rel="noreferrer" className="headline">
              <div className="headline-title">{h.title}</div>
              <div className="headline-src">
                {h.publication}
                {h.publishedAt ? ` · ${new Date(h.publishedAt).toLocaleDateString()}` : ""}
              </div>
            </a>
          ))}
        </div>
      )}
    </div>
  );
}

function MatchGroup({
  label,
  showHeader,
  matches,
  seeMore,
  back,
}: {
  label: string;
  showHeader: boolean;
  matches: LiveMatch[];
  seeMore: string | null;
  back: BackContext;
}) {
  if (matches.length === 0) return null;
  return (
    <section className="tourney-group">
      {showHeader && (
        <div className="tourney-head">
          <h2>{label}</h2>
          <span className="tour-badge">{matches.length}</span>
        </div>
      )}
      <MatchGrid
        matches={matches}
        grouped
        back={back}
        seeMoreHref={seeMore}
        seeMoreLabel={(total) => `See all ${total} →`}
      />
    </section>
  );
}

function Threads({
  threads,
  loading,
  tournamentName,
  id,
}: {
  threads?: TournamentThread[];
  loading: boolean;
  tournamentName: string;
  id: string;
}) {
  const router = useRouter();
  const back: BackContext = { label: tournamentName, href: `/tournaments/${id}` };
  if (loading) return <div className="spinner">Loading…</div>;
  if (!threads || threads.length === 0) {
    return <div className="empty">No chat threads for this tournament.</div>;
  }
  return (
    <div className="grid">
      {threads.map((th) => {
        const href = matchHref(th.matchExternalId, back, { thread: th.threadId });
        return (
        <article
          key={`${th.matchExternalId}:${th.threadId}`}
          className="card card-link thread-card"
          role="link"
          tabIndex={0}
          onClick={() => router.push(href)}
          onKeyDown={(e) => {
            if (e.key === "Enter") router.push(href);
          }}
        >
          <div className="thread-card-head">
            <span className="thread-title">{th.title}</span>
            <span className="active-pill">{th.activeChatters} active</span>
          </div>
          <MatchScoreMini m={th.match} />
          <span className="mini-meta">{th.messageCount} messages · by {th.authorName}</span>
        </article>
        );
      })}
    </div>
  );
}
