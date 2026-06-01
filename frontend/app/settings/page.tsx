"use client";

import useSWR from "swr";
import Link from "next/link";
import { apiFetch, fetcher } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import type { Favorite } from "@/lib/types";

export default function SettingsPage() {
  const { token, email, ready } = useAuth();
  const { data, mutate, isLoading } = useSWR<Favorite[]>(token ? "/api/me/favorites" : null, fetcher);

  if (ready && !token) {
    return (
      <div className="empty">
        Please <Link href="/login" className="player-link">log in</Link> to manage your favorites.
      </div>
    );
  }

  async function remove(playerId: number) {
    await apiFetch(`/api/me/favorites/${playerId}`, { method: "DELETE" });
    mutate();
  }

  return (
    <div>
      <h1>Settings</h1>
      <p className="sub">{email}</p>

      <h2>Favorite players</h2>
      {isLoading ? (
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
