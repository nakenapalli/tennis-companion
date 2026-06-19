"use client";

import Link from "next/link";
import useSWR from "swr";
import { fetcher } from "@/lib/api";
import { isMainTour, usePrefs } from "@/lib/prefs";
import type { Tournament } from "@/lib/types";

export default function TournamentsPage() {
  const { mainTourOnly } = usePrefs();
  const { data, isLoading } = useSWR<Tournament[]>("/api/tournaments/current", fetcher);
  const tournaments = (data ?? []).filter((t) => !mainTourOnly || isMainTour(t.level));

  return (
    <div>
      <h1>Tournaments</h1>
      <p className="sub">Currently active events.</p>

      {isLoading ? (
        <div className="spinner">Loading…</div>
      ) : tournaments.length === 0 ? (
        <div className="empty">No current ATP or WTA tournaments right now.</div>
      ) : (
        <div className="grid">
          {tournaments.map((t) => (
            <Link key={t.id} href={`/tournaments/${t.id}`} className="card card-link card-clickable">
              <div className="match-top"><strong>{t.name}</strong></div>
              <div className="sub">{[t.level, t.surface].filter(Boolean).join(" · ") || "—"}</div>
              <div className="muted" style={{ fontSize: 13 }}>
                {t.startDate ?? ""}
                {t.endDate ? ` → ${t.endDate}` : ""}
              </div>
            </Link>
          ))}
        </div>
      )}
    </div>
  );
}
