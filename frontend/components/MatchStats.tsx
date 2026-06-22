"use client";

import { useState } from "react";
import useSWR from "swr";
import { fetcher } from "@/lib/api";
import type { PlayerColors } from "@/lib/playerColors";
import type { MatchStats as MatchStatsData, StatRow } from "@/lib/types";

/** The Stats tab: per-period serve/return/points/games comparison with tug-of-war bars. */
export function MatchStats({ externalId, live, colors }: { externalId: string; live: boolean; colors: PlayerColors }) {
  const { data, error, isLoading } = useSWR<MatchStatsData>(
    `/api/matches/${externalId}/stats`,
    fetcher,
    { refreshInterval: live ? 20000 : 0, shouldRetryOnError: false },
  );
  const [period, setPeriod] = useState<string | null>(null);

  if (isLoading) return <div className="spinner">Loading stats…</div>;
  if (error || !data) return <div className="empty">Match stats aren&apos;t available for this match.</div>;

  const active = period && data.periods.includes(period) ? period : data.periods[0];
  const groups = data.groups[active] ?? [];

  return (
    <div>
      <div style={{ display: "flex", justifyContent: "center", gap: 6, marginBottom: 20 }}>
        {data.periods.map((p) => (
          <button
            key={p}
            type="button"
            onClick={() => setPeriod(p)}
            className={p === active ? "btn" : "btn ghost"}
            style={{ padding: "4px 14px", fontSize: 12 }}
          >
            {periodLabel(p)}
          </button>
        ))}
      </div>

      {/* Mirrored legend: equal-width name columns flanking a fixed center pair of squares, so the
          squares sit dead-center over the bars' divider regardless of name lengths. */}
      <div style={{ display: "flex", alignItems: "center", marginBottom: 22, fontSize: 13, fontWeight: 500 }}>
        <span style={{ flex: 1, textAlign: "right" }}>{data.player1}</span>
        <span style={{ display: "flex", gap: 3, margin: "0 10px" }}>
          <span style={{ width: 11, height: 11, borderRadius: 2, background: colors.c1 }} />
          <span style={{ width: 11, height: 11, borderRadius: 2, background: colors.c2 }} />
        </span>
        <span style={{ flex: 1, textAlign: "left" }}>{data.player2}</span>
      </div>

      {groups.map((g) => (
        <div key={g.type} style={{ marginBottom: 28 }}>
          <h3 style={{ fontSize: 12, fontWeight: 500, margin: "0 0 12px", textTransform: "uppercase", letterSpacing: "0.05em", color: "var(--muted)", textAlign: "center" }}>{g.type}</h3>
          {g.rows.map((r) => (
            <StatBar key={r.name} row={r} c1={colors.c1} c2={colors.c2} />
          ))}
        </div>
      ))}
    </div>
  );
}

function StatBar({ row, c1, c2 }: { row: StatRow; c1: string; c2: string }) {
  const n1 = magnitude(row.p1.value);
  const n2 = magnitude(row.p2.value);
  // Percentage stats (value has "%") use the raw value as each side's fill; integer stats use each
  // player's share of the combined total. Each fraction fills that player's half from the center out.
  const pct = (row.p1.value?.includes("%") ?? false) || (row.p2.value?.includes("%") ?? false);
  let f1: number;
  let f2: number;
  if (pct) {
    f1 = clamp01(n1 / 100);
    f2 = clamp01(n2 / 100);
  } else {
    const total = n1 + n2;
    f1 = total > 0 ? n1 / total : 0;
    f2 = total > 0 ? n2 / total : 0;
  }

  return (
    <div style={{ marginBottom: 16 }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "baseline", fontSize: 13, marginBottom: 6, gap: 12 }}>
        <span style={{ fontWeight: n1 >= n2 ? 500 : 400, minWidth: 64 }}>{display(row.p1)}</span>
        <span className="muted" style={{ fontSize: 12, textAlign: "center", flex: 1 }}>{row.name}</span>
        <span style={{ fontWeight: n2 >= n1 ? 500 : 400, minWidth: 64, textAlign: "right" }}>{display(row.p2)}</span>
      </div>
      <div style={{ display: "flex", alignItems: "stretch", height: 6 }}>
        <div style={{ flex: 1, display: "flex", justifyContent: "flex-end", background: "var(--panel-2)", borderRadius: "3px 0 0 3px", overflow: "hidden" }}>
          <div style={{ width: `${(f1 * 100).toFixed(1)}%`, background: c1 }} />
        </div>
        <div style={{ width: 1, background: "var(--line)" }} />
        <div style={{ flex: 1, display: "flex", justifyContent: "flex-start", background: "var(--panel-2)", borderRadius: "0 3px 3px 0", overflow: "hidden" }}>
          <div style={{ width: `${(f2 * 100).toFixed(1)}%`, background: c2 }} />
        </div>
      </div>
    </div>
  );
}

const clamp01 = (x: number) => Math.max(0, Math.min(1, x));

/** Numeric magnitude from a display value ("60%" → 60, "10" → 10, null → 0) for the bar split. */
function magnitude(value: string | null): number {
  if (!value) return 0;
  const n = parseFloat(value.replace("%", ""));
  return Number.isFinite(n) ? n : 0;
}

/** Show the value, with the won/total ratio when present ("70%  33/47"). */
function display(cell: { value: string | null; won: number | null; total: number | null }): string {
  if (cell.value == null) return "—";
  if (cell.won != null && cell.total != null) return `${cell.value} · ${cell.won}/${cell.total}`;
  return cell.value;
}

function periodLabel(p: string): string {
  if (p === "match") return "Match";
  const m = p.match(/^set(\d+)$/);
  return m ? `Set ${m[1]}` : p;
}
