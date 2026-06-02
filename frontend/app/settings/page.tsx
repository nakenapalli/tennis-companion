"use client";

import useSWR from "swr";
import Link from "next/link";
import { apiFetch, fetcher } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { usePrefs } from "@/lib/prefs";
import type { Favorite } from "@/lib/types";

export default function SettingsPage() {
  const { token, ready } = useAuth();
  const { mainTourOnly, setMainTourOnly } = usePrefs();
  const { data, mutate, isLoading } = useSWR<Favorite[]>(token ? "/api/me/favorites" : null, fetcher);

  async function remove(playerId: number) {
    await apiFetch(`/api/me/favorites/${playerId}`, { method: "DELETE" });
    mutate();
  }

  return (
    <div>
      <h1>Settings</h1>

      <h2>Display</h2>
      <p className="sub">Which tournament types to show in scores and tournaments.</p>
      <div className="toggle">
        <button className={mainTourOnly ? "on" : ""} onClick={() => setMainTourOnly(true)}>ATP &amp; WTA</button>
        <button className={!mainTourOnly ? "on" : ""} onClick={() => setMainTourOnly(false)}>All tournaments</button>
      </div>
      <p className="muted" style={{ fontSize: 13, marginTop: 8 }}>
        {mainTourOnly
          ? "Showing ATP & WTA only — Challenger, ITF and junior events are hidden."
          : "Showing all tournament types."}
      </p>

      <h2 style={{ marginTop: 32 }}>Favorite players</h2>
      {!ready ? null : !token ? (
        <div className="empty">
          Please <Link href="/login" className="player-link">log in</Link> to manage your favorites.
        </div>
      ) : isLoading ? (
        <div className="spinner">Loading…</div>
      ) : !data || data.length === 0 ? (
        <div className="empty">No favorites yet — add players from their profile page.</div>
      ) : (
        <div className="grid">
          {data.map((f) => (
            <article key={f.playerId} className="card row-between">
              <Link href={`/players/${f.playerId}`} className="player-link">
                {f.firstName} {f.lastName} <span className="muted">{f.tour}</span>
              </Link>
              <button className="btn-link" onClick={() => remove(f.playerId)}>Remove</button>
            </article>
          ))}
        </div>
      )}
    </div>
  );
}
