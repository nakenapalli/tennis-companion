"use client";

import { useState } from "react";
import useSWR from "swr";
import Link from "next/link";
import { fetcher } from "@/lib/api";
import type { RankingRow } from "@/lib/types";

export default function RankingsPage() {
  const [tour, setTour] = useState<"ATP" | "WTA">("ATP");
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
        <table>
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
                <td className="muted">{r.country ?? ""}</td>
                <td>{r.points ?? ""}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
