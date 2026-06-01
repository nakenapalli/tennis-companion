"use client";

import { useState } from "react";
import { useParams } from "next/navigation";
import Link from "next/link";
import useSWR from "swr";
import { apiFetch, fetcher } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import type { MatchDto, PlayerProfile } from "@/lib/types";

export default function PlayerPage() {
  const { id } = useParams<{ id: string }>();
  const profile = useSWR<PlayerProfile>(`/api/players/${id}`, fetcher);
  const matches = useSWR<MatchDto[]>(`/api/players/${id}/matches?limit=15`, fetcher);
  const { token } = useAuth();
  const [faved, setFaved] = useState(false);

  if (profile.isLoading) return <div className="spinner">Loading…</div>;
  if (profile.error || !profile.data) return <div className="empty">Player not found.</div>;

  const p = profile.data;
  const meta = [p.tour, p.country, p.currentRank ? `Rank #${p.currentRank}` : null, p.hand ? `${p.hand}-handed` : null]
    .filter(Boolean)
    .join(" · ");

  async function addFavorite() {
    await apiFetch("/api/me/favorites", { method: "POST", body: JSON.stringify({ playerId: p.playerId }) });
    setFaved(true);
  }

  return (
    <div>
      <div className="row-between">
        <div>
          <h1>{p.firstName} {p.lastName}</h1>
          <p className="sub">{meta}</p>
        </div>
        {token && (
          <button className="fav-btn" disabled={faved} onClick={addFavorite}>
            {faved ? "★ Added" : "☆ Add to favorites"}
          </button>
        )}
      </div>

      <h2>Recent matches</h2>
      {matches.data && matches.data.length > 0 ? (
        <table>
          <thead>
            <tr>
              <th>Date</th>
              <th>Tournament</th>
              <th>Rd</th>
              <th></th>
              <th>Opponent</th>
              <th>Score</th>
            </tr>
          </thead>
          <tbody>
            {matches.data.map((m, i) => (
              <tr key={i}>
                <td className="muted">{m.tourneyDate?.slice(0, 10)}</td>
                <td>{m.tourneyName}</td>
                <td className="muted">{m.round}</td>
                <td><span className={m.result === "W" ? "pill" : "muted"}>{m.result}</span></td>
                <td>
                  {m.opponentId ? (
                    <Link href={`/players/${m.opponentId}`} className="player-link">{m.opponentName}</Link>
                  ) : (
                    m.opponentName
                  )}
                </td>
                <td className="muted">{m.score}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <div className="empty">No recent matches in the loaded data.</div>
      )}
    </div>
  );
}
