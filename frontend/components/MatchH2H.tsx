"use client";

import useSWR from "swr";
import { fetcher } from "@/lib/api";
import type { PlayerColors } from "@/lib/playerColors";
import type { H2hView } from "@/lib/types";

/** The Head-to-head tab: career record + a list of prior meetings (Sackmann history, or live feed). */
export function MatchH2H({ externalId, live, colors }: { externalId: string; live: boolean; colors: PlayerColors }) {
  const { data, error, isLoading } = useSWR<H2hView>(
    `/api/matches/${externalId}/h2h`,
    fetcher,
    { refreshInterval: live ? 60000 : 0, shouldRetryOnError: false },
  );

  if (isLoading) return <div className="spinner">Loading head-to-head…</div>;
  if (error || !data) return <div className="empty">No head-to-head history for this match.</div>;

  return (
    <div>
      <div style={{ display: "flex", alignItems: "center", justifyContent: "center", gap: 16, marginBottom: 4 }}>
        <span style={{ flex: 1, textAlign: "right", fontWeight: 500 }}>{data.player1}</span>
        <span style={{ fontVariantNumeric: "tabular-nums", fontSize: 22, fontWeight: 500, whiteSpace: "nowrap" }}>
          <span style={{ color: colors.c1 }}>{data.p1Wins}</span>
          <span className="muted" style={{ margin: "0 6px" }}>–</span>
          <span style={{ color: colors.c2 }}>{data.p2Wins}</span>
        </span>
        <span style={{ flex: 1, textAlign: "left", fontWeight: 500 }}>{data.player2}</span>
      </div>
      <p className="muted" style={{ textAlign: "center", fontSize: 12, marginTop: 0, marginBottom: 20 }}>
        {data.meetings.length === 0
          ? "No previous meetings"
          : `${data.meetings.length} meeting${data.meetings.length === 1 ? "" : "s"}`}
      </p>

      {data.meetings.length > 0 && (
        <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
          {data.meetings.map((mtg, i) => (
            <div
              key={i}
              className="card"
              style={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: 12, padding: "8px 12px" }}
            >
              <div style={{ minWidth: 0 }}>
                <div style={{ fontSize: 13 }}>
                  {mtg.tournament ?? "—"}
                  {mtg.round && <span className="muted"> · {mtg.round}</span>}
                </div>
                <div className="muted" style={{ fontSize: 12 }}>
                  {(mtg.date ?? "").slice(0, 10)}
                  {mtg.surface && ` · ${mtg.surface}`}
                </div>
              </div>
              <div style={{ textAlign: "right", whiteSpace: "nowrap" }}>
                <span style={{ fontSize: 12, fontWeight: 500, color: mtg.winner === 1 ? colors.c1 : colors.c2 }}>
                  {mtg.winner === 1 ? data.player1 : data.player2}
                </span>
                {mtg.score && <span className="muted" style={{ fontSize: 12, marginLeft: 8, fontVariantNumeric: "tabular-nums" }}>{mtg.score}</span>}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
