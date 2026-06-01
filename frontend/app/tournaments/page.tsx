"use client";

import useSWR from "swr";
import { fetcher } from "@/lib/api";
import type { Tournament } from "@/lib/types";

export default function TournamentsPage() {
  const { data, isLoading } = useSWR<Tournament[]>("/api/tournaments/current", fetcher);

  return (
    <div>
      <h1>Tournaments</h1>
      <p className="sub">Currently active events.</p>

      {isLoading ? (
        <div className="spinner">Loading…</div>
      ) : !data || data.length === 0 ? (
        <div className="empty">No current tournaments — an admin needs to run a tournament sync.</div>
      ) : (
        <div className="grid">
          {data.map((t) => (
            <article key={t.id} className="card">
              <div className="match-top"><strong>{t.name}</strong></div>
              <div className="sub">{[t.level, t.surface].filter(Boolean).join(" · ") || "—"}</div>
              <div className="muted" style={{ fontSize: 13 }}>
                {t.startDate ?? ""}
                {t.endDate ? ` → ${t.endDate}` : ""}
              </div>
            </article>
          ))}
        </div>
      )}
    </div>
  );
}
