"use client";

import { useState } from "react";
import Link from "next/link";
import useSWR from "swr";
import { apiFetch, fetcher } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import type { ReviewCandidate, UnmappedEntity } from "@/lib/types";

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
        Upstream players the matcher (Tiers 0–3) couldn&apos;t confidently map. Pick the right canonical
        player to confirm a mapping — it becomes an instant cache hit next time.
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

function ReviewRow({ entity, onConfirmed }: { entity: UnmappedEntity; onConfirmed: () => void }) {
  const [open, setOpen] = useState(false);
  const [candidates, setCandidates] = useState<ReviewCandidate[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function toggle() {
    const next = !open;
    setOpen(next);
    if (next && candidates === null) {
      setLoading(true);
      setError(null);
      try {
        const q = new URLSearchParams({ source: entity.source, externalPlayerId: entity.externalPlayerId });
        setCandidates(await apiFetch<ReviewCandidate[]>(`/api/admin/unmapped-entities/candidates?${q}`));
      } catch {
        setError("Couldn't load candidates.");
      } finally {
        setLoading(false);
      }
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

  return (
    <article className="card">
      <div className="row-between">
        <div>
          <strong>{entity.externalName ?? "(no name)"}</strong>{" "}
          <span className="muted">
            {entity.source} · {entity.externalPlayerId}
          </span>
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
          {loading ? (
            <div className="spinner">Loading candidates…</div>
          ) : error ? (
            <div className="error">{error}</div>
          ) : !candidates || candidates.length === 0 ? (
            <div className="muted" style={{ fontSize: 13 }}>
              No candidates by surname — this player may not be in the historical dataset.
            </div>
          ) : (
            <div className="grid">
              {candidates.map((c) => (
                <div key={c.playerId} className="card row-between" style={{ padding: "8px 12px" }}>
                  <Link href={`/players/${c.playerId}`} className="player-link" target="_blank">
                    {c.name}{" "}
                    <span className="muted">
                      {[c.country, c.birthYear].filter(Boolean).join(" · ")}
                    </span>
                  </Link>
                  <button className="btn" disabled={busyId !== null} onClick={() => confirm(c.playerId)}>
                    {busyId === c.playerId ? "…" : "Confirm"}
                  </button>
                </div>
              ))}
              {error && <div className="error">{error}</div>}
            </div>
          )}
        </div>
      )}
    </article>
  );
}
