"use client";

import { Suspense, useState } from "react";
import { useSearchParams } from "next/navigation";
import useSWR from "swr";
import Link from "next/link";
import { fetcher } from "@/lib/api";
import { flagEmoji } from "@/lib/flags";
import type { RankingRow } from "@/lib/types";

export default function RankingsPage() {
  // useSearchParams must sit under a Suspense boundary for the production build to prerender the page.
  return (
    <Suspense fallback={<div className="spinner">Loading…</div>}>
      <Rankings />
    </Suspense>
  );
}

function Rankings() {
  // Seed the selected tour from ?tour= (e.g. the home page's "See all" links); default ATP.
  const initialTour = useSearchParams().get("tour") === "WTA" ? "WTA" : "ATP";
  const [tour, setTour] = useState<"ATP" | "WTA">(initialTour);
  const { data, isLoading } = useSWR<RankingRow[]>(`/api/rankings?tour=${tour}&limit=100`, fetcher);

  return (
    <div>
      <div className="row-between">
        <div>
          <h1>Rankings</h1>
          <p className="sub">Current {tour} singles.</p>
        </div>
        <div className="toggle">
          <button className={tour === "ATP" ? "on" : ""} onClick={() => setTour("ATP")}>ATP</button>
          <button className={tour === "WTA" ? "on" : ""} onClick={() => setTour("WTA")}>WTA</button>
        </div>
      </div>

      {isLoading ? (
        <div className="spinner">Loading…</div>
      ) : !data || data.length === 0 ? (
        <div className="empty">No rankings loaded yet — an admin needs to run a rankings poll.</div>
      ) : (
        <table className="rankings-table">
          <thead>
            <tr>
              <th>#</th>
              <th>Player</th>
              <th>Country</th>
              <th>Points</th>
            </tr>
          </thead>
          <tbody>
            {data.map((r) => (
              <tr key={r.rank}>
                <td className="rank-num">{r.rank}</td>
                <td>
                  {r.playerId ? (
                    <Link href={`/players/${r.playerId}`} className="player-link">{r.name}</Link>
                  ) : (
                    r.name
                  )}
                </td>
                <td>
                  {flagEmoji(r.country) && <span className="flag">{flagEmoji(r.country)}</span>}
                  <span className="muted">{r.country ?? ""}</span>
                </td>
                <td>{r.points ?? ""}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
