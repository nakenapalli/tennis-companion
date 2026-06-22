"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import useSWR from "swr";
import { apiFetch, fetcher } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { flagEmoji } from "@/lib/flags";
import type { ReviewCandidate, UnmappedEntity, UpstreamMatch, UpstreamProfile } from "@/lib/types";

export default function AdminReconciliationPage() {
  const { admin, ready } = useAuth();
  const { data, mutate, isLoading } = useSWR<UnmappedEntity[]>(
    admin ? "/api/admin/unmapped-entities?limit=200" : null,
    fetcher,
  );

  if (!ready) return null;
  if (!admin) {
    return (
      <div>
        <h1>Reconciliation review</h1>
        <div className="empty">
          Admins only. Please <Link href="/login" className="player-link">log in</Link> with an admin account.
        </div>
      </div>
    );
  }

  return (
    <div>
      <h1>Reconciliation review</h1>
      <p className="sub">
        Upstream players the matcher (Tiers 0–3) couldn&apos;t confidently map. Use the player&apos;s
        country, rank, and recent results to pick the right canonical player — confirming makes it an
        instant cache hit next time.
      </p>

      {isLoading ? (
        <div className="spinner">Loading…</div>
      ) : !data || data.length === 0 ? (
        <div className="empty">Nothing to review — the queue is empty. 🎉</div>
      ) : (
        <div className="grid">
          {data.map((e) => (
            <ReviewRow key={`${e.source}:${e.externalPlayerId}`} entity={e} onConfirmed={() => mutate()} />
          ))}
        </div>
      )}
    </div>
  );
}

/** Compact country tag: flag emoji where we can map the IOC code, else the code text. */
function Country({ code }: { code?: string }) {
  if (!code) return null;
  const flag = flagEmoji(code);
  return <span className="muted">{flag ? `${flag} ` : ""}{code}</span>;
}

function ReviewRow({ entity, onConfirmed }: { entity: UnmappedEntity; onConfirmed: () => void }) {
  const [open, setOpen] = useState(false);
  const [candidates, setCandidates] = useState<ReviewCandidate[] | null>(null);
  const [matches, setMatches] = useState<UpstreamMatch[] | null>(null);
  const [profile, setProfile] = useState<UpstreamProfile | null>(null);
  const [loading, setLoading] = useState(false);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const q = new URLSearchParams({ source: entity.source, externalPlayerId: entity.externalPlayerId }).toString();

  // If the row was already enriched (stored country/rank/birth year), use that — no upstream call.
  const enriched = entity.country != null || entity.rankHint != null || entity.birthYear != null;

  // Otherwise fetch the profile up front (not on expand) so country/rank/birth year show on the
  // collapsed card; the backend persists it, so each player is fetched once and is free thereafter.
  // Browsers cap concurrent requests, so these trickle across the queue rather than hammering the server.
  useEffect(() => {
    if (enriched) return;
    let active = true;
    apiFetch<UpstreamProfile>(`/api/admin/unmapped-entities/upstream-profile?${q}`)
      .then((p) => active && setProfile(p))
      .catch(() => active && setProfile({}));
    return () => { active = false; };
  }, [q, enriched]);

  async function toggle() {
    const next = !open;
    setOpen(next);
    if (next && candidates === null) {
      setLoading(true);
      setError(null);
      // Candidates + recent results load in parallel; one failing shouldn't block the other.
      const [cands, recent] = await Promise.allSettled([
        apiFetch<ReviewCandidate[]>(`/api/admin/unmapped-entities/candidates?${q}`),
        apiFetch<UpstreamMatch[]>(`/api/admin/unmapped-entities/upstream-matches?${q}`),
      ]);
      if (cands.status === "fulfilled") setCandidates(cands.value);
      else { setCandidates([]); setError("Couldn't load candidates."); }
      setMatches(recent.status === "fulfilled" ? recent.value : []);
      setLoading(false);
    }
  }

  async function confirm(playerId: string) {
    setBusyId(playerId);
    setError(null);
    try {
      await apiFetch("/api/admin/entity-map", {
        method: "POST",
        body: JSON.stringify({ source: entity.source, externalPlayerId: entity.externalPlayerId, playerId }),
      });
      onConfirmed(); // row drops out of the (refetched) queue
    } catch {
      setError("Couldn't confirm the mapping.");
      setBusyId(null);
    }
  }

  // Prefer stored (already-enriched) values; fall back to a freshly fetched profile for un-enriched rows.
  const country = entity.country ?? profile?.country;
  const rank = entity.rankHint ?? profile?.rank;
  const birthYear = entity.birthYear ?? profile?.birthYear;

  return (
    <article className="card">
      <div className="row-between">
        <div>
          <strong>{entity.externalName ?? "(no name)"}</strong>{" "}
          <Country code={country} />
          {rank != null && <span className="muted"> · #{rank}</span>}
          {birthYear != null && <span className="muted"> · b. {birthYear}</span>}
          <div className="muted" style={{ fontSize: 13, marginTop: 4 }}>
            {entity.tier ?? "—"}
            {entity.confidence != null && ` · confidence ${entity.confidence.toFixed(2)}`}
            {entity.rationale && ` · ${entity.rationale}`}
          </div>
        </div>
        <button className="btn-link" onClick={toggle}>
          {open ? "Hide" : "Find matches"}
        </button>
      </div>

      {open && (
        <div style={{ marginTop: 12 }}>
          <div className="muted" style={{ fontSize: 13, marginBottom: 12 }}>
            {entity.source} · {entity.externalPlayerId}
          </div>
          {loading ? (
            <div className="spinner">Loading…</div>
          ) : (
            <>
              <RecentResults matches={matches} />

              <h3 style={{ fontSize: 14, margin: "16px 0 8px" }}>Possible matches</h3>
              {error && <div className="error">{error}</div>}
              {!candidates || candidates.length === 0 ? (
                <div className="muted" style={{ fontSize: 13 }}>
                  No candidates by surname — this player may not be in the historical dataset.
                </div>
              ) : (
                <div className="grid">
                  {candidates.map((c) => (
                    <div key={c.playerId} className="card row-between" style={{ padding: "8px 12px" }}>
                      <Link href={`/players/${c.playerId}`} className="player-link" target="_blank">
                        {c.name}{" "}
                        <Country code={c.country} />
                        {c.birthYear != null && <span className="muted"> · b. {c.birthYear}</span>}
                      </Link>
                      <button className="btn" disabled={busyId !== null} onClick={() => confirm(c.playerId)}>
                        {busyId === c.playerId ? "…" : "Confirm"}
                      </button>
                    </div>
                  ))}
                </div>
              )}
            </>
          )}
        </div>
      )}
    </article>
  );
}

/** The upstream player's recent results (live from API Tennis) — the strongest "is this them?" cue. */
function RecentResults({ matches }: { matches: UpstreamMatch[] | null }) {
  return (
    <>
      <h3 style={{ fontSize: 14, margin: "0 0 8px" }}>Recent results <span className="muted">(API Tennis)</span></h3>
      {!matches || matches.length === 0 ? (
        <div className="muted" style={{ fontSize: 13 }}>No recent results found for this player.</div>
      ) : (
        <div style={{ display: "flex", flexDirection: "column", gap: 4 }}>
          {matches.map((m, i) => (
            <div key={i} className="muted" style={{ fontSize: 13 }}>
              {m.date && <span>{m.date} · </span>}
              {m.result && (
                <strong style={{ color: m.result === "W" ? "var(--win, #2e7d32)" : undefined }}>{m.result} </strong>
              )}
              vs {m.opponentName ?? "?"}
              {m.score && <span> {m.score}</span>}
              {(m.tournamentName || m.round) && (
                <span> — {[m.tournamentName, m.round].filter(Boolean).join(", ")}</span>
              )}
            </div>
          ))}
        </div>
      )}
    </>
  );
}
