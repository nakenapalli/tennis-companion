"use client";

import { useState } from "react";
import { useParams, useRouter } from "next/navigation";
import Link from "next/link";
import useSWR from "swr";
import { fetcher } from "@/lib/api";
import type { Headline, LiveMatch, Tournament, TournamentThread } from "@/lib/types";
import { MatchCard } from "@/components/MatchCard";
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

          {tab === "overview" ? <Overview id={id} /> : <Threads threads={threads.data} loading={threads.isLoading} />}
        </>
      )}
    </div>
  );
}

function Overview({ id }: { id: string }) {
  const { data: matches, isLoading } = useSWR<LiveMatch[]>(`/api/tournaments/${id}/matches`, fetcher, { refreshInterval: 30000 });
  const headlines = useSWR<Headline[]>(`/api/tournaments/${id}/headlines`, fetcher);

  const all = matches ?? [];
  // Only split into ATP/WTA sections when the tournament actually spans both tours (Slams, Masters combos).
  // A single-tour event (the common case) renders as one flat grid with no redundant header.
  const groups = [
    { label: "ATP", matches: all.filter((m) => m.tour === "ATP") },
    { label: "WTA", matches: all.filter((m) => m.tour === "WTA") },
    { label: "Other", matches: all.filter((m) => m.tour !== "ATP" && m.tour !== "WTA") },
  ].filter((g) => g.matches.length > 0);

  return (
    <div>
      {isLoading ? (
        <div className="spinner">Loading…</div>
      ) : all.length === 0 ? (
        <div className="empty">No recent or live matches for this tournament right now.</div>
      ) : groups.length > 1 ? (
        groups.map((g) => <MatchGroup key={g.label} label={g.label} matches={g.matches} />)
      ) : (
        <div className="grid">
          {all.map((m) => (
            <MatchCard key={m.externalId} m={m} grouped />
          ))}
        </div>
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

function MatchGroup({ label, matches }: { label: string; matches: LiveMatch[] }) {
  if (matches.length === 0) return null;
  return (
    <section className="tourney-group">
      <div className="tourney-head">
        <h2>{label}</h2>
        <span className="tour-badge">{matches.length}</span>
      </div>
      <div className="grid">
        {matches.map((m) => (
          <MatchCard key={m.externalId} m={m} grouped />
        ))}
      </div>
    </section>
  );
}

function Threads({ threads, loading }: { threads?: TournamentThread[]; loading: boolean }) {
  const router = useRouter();
  if (loading) return <div className="spinner">Loading…</div>;
  if (!threads || threads.length === 0) {
    return <div className="empty">No chat threads for this tournament.</div>;
  }
  return (
    <div className="grid">
      {threads.map((th) => (
        <article
          key={`${th.matchExternalId}:${th.threadId}`}
          className="card card-link thread-card"
          role="link"
          tabIndex={0}
          onClick={() => router.push(`/matches/${th.matchExternalId}?thread=${th.threadId}`)}
          onKeyDown={(e) => {
            if (e.key === "Enter") router.push(`/matches/${th.matchExternalId}?thread=${th.threadId}`);
          }}
        >
          <div className="thread-card-head">
            <span className="thread-title">{th.title}</span>
            <span className="active-pill">{th.activeChatters} active</span>
          </div>
          <MatchScoreMini m={th.match} />
          <span className="mini-meta">{th.messageCount} messages · by {th.authorName}</span>
        </article>
      ))}
    </div>
  );
}
