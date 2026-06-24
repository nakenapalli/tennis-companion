"use client";

import { useEffect, useRef } from "react";
import useSWR from "swr";
import { Chart, registerables, type ChartConfiguration, type TooltipModel } from "chart.js";
import { fetcher } from "@/lib/api";
import type { PlayerColors } from "@/lib/playerColors";
import type { Momentum } from "@/lib/types";

Chart.register(...registerables);

const AXIS = "#8b97a7";
const LINE = "rgba(139,151,167,0.5)";
const NEUTRAL_HL = "rgba(201,209,217,0.13)"; // highlight band for the (player-agnostic) heaviest game

/**
 * The Momentum tab: our bespoke momentum metric (server-computed from the point-by-point flow) as a
 * signed line — player 1 above the axis, player 2 below — with break markers and set brackets. Hovering
 * anywhere on the line shows the running match score at that point.
 */
export function MatchMomentum({ externalId, live, colors }: { externalId: string; live: boolean; colors: PlayerColors }) {
  const { data, error, isLoading } = useSWR<Momentum>(
    `/api/matches/${externalId}/momentum`,
    fetcher,
    { refreshInterval: live ? 20000 : 0, shouldRetryOnError: false },
  );
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const chartRef = useRef<Chart | null>(null);
  const highlightRef = useRef<{ a: number; b: number; color: string } | null>(null);

  useEffect(() => {
    if (!data || !canvasRef.current) return;
    const { series, breaks, sets, player1, player2 } = data;

    const bracketPlugin = {
      id: "setBrackets",
      afterDraw(chart: Chart) {
        const ctx = chart.ctx;
        const xs = chart.scales.x;
        const top = chart.chartArea.bottom + 30;
        const tick = 7;
        ctx.save();
        ctx.strokeStyle = AXIS;
        ctx.fillStyle = AXIS;
        ctx.lineWidth = 1;
        ctx.textAlign = "center";
        ctx.textBaseline = "top";
        ctx.font = "500 12px system-ui, sans-serif";
        for (let i = 0; i < sets.length; i++) {
          const s = sets[i];
          const x0 = xs.getPixelForValue(s.startX) + (i === 0 ? 0 : 3);
          const x1 = xs.getPixelForValue(s.endX) - (i === sets.length - 1 ? 0 : 3);
          const mid = (x0 + x1) / 2;
          ctx.beginPath();
          ctx.moveTo(x0, top);
          ctx.lineTo(x0, top + tick);
          ctx.lineTo(x1, top + tick);
          ctx.lineTo(x1, top);
          ctx.stroke();
          ctx.fillText(`${s.label} · ${s.score}`, mid, top + tick + 5);
        }
        ctx.restore();
      },
    };

    // Custom HTML tooltip: a mini scoreboard (abbreviated names + per-set/current games), plus an
    // emphatic "Break!" callout naming the breaker when hovering at a break point.
    const externalTooltip = (ctx: { chart: Chart; tooltip: TooltipModel<"line"> }) => {
      const { chart, tooltip } = ctx;
      const parent = chart.canvas.parentNode as HTMLElement;
      let el = parent.querySelector<HTMLDivElement>(".mom-tip");
      if (!el) {
        el = document.createElement("div");
        el.className = "mom-tip";
        el.style.cssText =
          "position:absolute;pointer-events:none;background:var(--panel);border:1px solid var(--line);" +
          "border-radius:8px;padding:8px 10px;transform:translate(-50%,calc(-100% - 12px));transition:opacity .08s;" +
          "z-index:5;white-space:nowrap;color:var(--text);box-shadow:0 4px 14px rgba(0,0,0,0.45);";
        parent.appendChild(el);
      }
      if (tooltip.opacity === 0) { el.style.opacity = "0"; return; }
      const lp = (tooltip.dataPoints || []).find((d) => d.datasetIndex === 0);
      if (!lp) { el.style.opacity = "0"; return; }
      const p = series[lp.dataIndex];
      const x = Number(lp.parsed.x);
      const y = Number(lp.parsed.y) || 0;

      const setsArr = p?.sets ? p.sets.split(", ").map((s) => s.split("-")) : [];
      const cur = (p?.games || "0-0").split("-");
      const pts = (p?.points || "").split("-"); // in-game point score ("" on a game-ending sample)
      const brk = breaks.find((b) => b.x === x);
      const row = (name: string, i: number) => {
        const pill =
          brk && brk.by === i + 1
            ? `<span style="margin-left:6px;padding:1px 6px;border-radius:6px;background:${i === 0 ? colors.c1 : colors.c2};` +
              `color:#0e1116;font-size:10px;font-weight:600;vertical-align:middle">Break</span>`
            : "";
        // serve indicator: a dot in the server's colour (blank spacer for the other player keeps names aligned)
        const serve =
          p?.server === i + 1
            ? `<span style="display:inline-block;width:7px;height:7px;border-radius:50%;background:${i === 0 ? colors.c1 : colors.c2};margin-right:6px;vertical-align:middle"></span>`
            : `<span style="display:inline-block;width:7px;margin-right:6px"></span>`;
        const setCells = setsArr
          .map((s) => `<td style="padding:0 5px;text-align:center;color:var(--muted)">${s[i] ?? ""}</td>`)
          .join("");
        const curCell = `<td style="padding:0 5px;text-align:center;font-weight:600;color:var(--text)">${cur[i] ?? ""}</td>`;
        // current-game point score, divided from the games columns; blank on a game-ending sample
        const ptsCell = `<td style="padding:0 4px 0 8px;text-align:center;font-weight:600;color:${i === 0 ? colors.c1 : colors.c2};border-left:1px solid rgba(255,255,255,0.12)">${pts[i] ?? ""}</td>`;
        return `<tr><td style="padding:0 10px 0 0;white-space:nowrap">${serve}${name}${pill}</td>${setCells}${curCell}${ptsCell}</tr>`;
      };
      const board =
        `<table style="border-collapse:collapse;font-variant-numeric:tabular-nums;font-size:12px;line-height:1.55">` +
        `${row(lastName(player1), 0)}${row(lastName(player2), 1)}</table>`;

      const lead = y === 0 ? "level" : y > 0 ? `${lastName(player1)} +${y.toFixed(2)}` : `${lastName(player2)} +${(-y).toFixed(2)}`;
      const footer = `<div style="font-size:11px;color:var(--muted);margin-top:5px">Point ${x} · ${lead}</div>`;

      el.innerHTML = board + footer;
      el.style.left = `${chart.canvas.offsetLeft + tooltip.caretX}px`;
      el.style.top = `${chart.canvas.offsetTop + tooltip.caretY}px`;
      el.style.opacity = "1";
    };

    // Shaded band over a meta-card's event region, set on card hover via highlightRef.
    const highlightPlugin = {
      id: "eventHighlight",
      beforeDatasetsDraw(chart: Chart) {
        const h = highlightRef.current;
        if (!h) return;
        const xs = chart.scales.x;
        const area = chart.chartArea;
        const x0 = xs.getPixelForValue(h.a);
        const x1 = xs.getPixelForValue(h.b);
        const left = Math.min(x0, x1);
        const w = Math.max(Math.abs(x1 - x0), 6); // min width so a single-point swing is visible
        const ctx = chart.ctx;
        ctx.save();
        ctx.fillStyle = h.color;
        ctx.fillRect(left, area.top, w, area.bottom - area.top);
        ctx.restore();
      },
    };

    const maxX = series.length ? series[series.length - 1].x : 1;
    const config: ChartConfiguration = {
      type: "line",
      data: {
        datasets: [
          {
            type: "line",
            label: "Momentum",
            data: series.map((p) => ({ x: p.x, y: p.y })),
            parsing: false,
            borderColor: "#c9d1d9",
            borderWidth: 1.5,
            pointRadius: 0,
            tension: 0.3,
            fill: { target: "origin", above: colors.fill1, below: colors.fill2 },
          },
          {
            type: "scatter",
            label: "Breaks",
            data: breaks.map((b) => ({ x: b.x, y: b.y })),
            parsing: false,
            pointStyle: "rectRot",
            pointRadius: 6,
            pointHoverRadius: 7,
            pointBackgroundColor: breaks.map((b) => (b.by === 1 ? colors.c1 : colors.c2)),
            pointBorderColor: "#0e1116",
            pointBorderWidth: 1.5,
          },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        layout: { padding: { bottom: 52, top: 4 } },
        interaction: { mode: "index", intersect: false },
        scales: {
          x: {
            type: "linear",
            min: 0,
            max: maxX,
            ticks: { stepSize: Math.max(10, Math.round(maxX / 7 / 10) * 10), color: AXIS, font: { size: 11 } },
            grid: { display: false },
            border: { color: "#2a3340" },
          },
          y: {
            min: -1,
            max: 1,
            ticks: { stepSize: 0.5, color: AXIS, font: { size: 11 }, callback: (v) => Number(v).toFixed(1) },
            grid: { color: (c) => (c.tick.value === 0 ? LINE : "rgba(139,151,167,0.12)") },
            border: { display: false },
          },
        },
        plugins: {
          legend: { display: false },
          tooltip: { enabled: false, external: externalTooltip },
        },
      },
      plugins: [bracketPlugin, highlightPlugin],
    };

    chartRef.current = new Chart(canvasRef.current, config);
    return () => chartRef.current?.destroy();
  }, [data, colors]);

  const showHighlight = (a: number, b: number, color: string) => {
    highlightRef.current = { a, b, color };
    chartRef.current?.draw();
  };
  const hideHighlight = () => {
    highlightRef.current = null;
    chartRef.current?.draw();
  };

  if (isLoading) return <div className="spinner">Loading momentum…</div>;
  if (error || !data) return <div className="empty">Momentum isn&apos;t available for this match.</div>;

  const m = data.meta;

  return (
    <div>

      <div style={{ display: "flex", gap: 0 }}>
        <div style={{ width: 24, flexShrink: 0, display: "flex", flexDirection: "column", paddingBottom: 52 }}>
          <AxisName name={data.player1} color={colors.c1} />
          <AxisName name={data.player2} color={colors.c2} />
        </div>
        <div style={{ flex: 1, minWidth: 0, position: "relative", height: 360 }}>
          <canvas
            ref={canvasRef}
            role="img"
            aria-label={`Momentum line for ${data.player1} versus ${data.player2}, ending at ${data.series.at(-1)?.y ?? 0}.`}
          />
        </div>
      </div>

      
      <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(150px, 1fr))", gap: 10 }}>
        <MetaCard
          label="Largest streak"
          value={`${m.largestStreak} games · ${lastName(m.streakSide === 1 ? data.player1 : data.player2)}`}
          onEnter={() => showHighlight(m.streakStartX, m.streakEndX, m.streakSide === 1 ? colors.hl1 : colors.hl2)}
          onLeave={hideHighlight}
        />
        <MetaCard
          label="Heaviest game"
          value={m.heaviestGame || "—"}
          onEnter={() => showHighlight(m.heaviestStartX, m.heaviestEndX, NEUTRAL_HL)}
          onLeave={hideHighlight}
        />
        <MetaCard
          label="Biggest swing"
          value={`${m.biggestSwing.toFixed(2)} · ${lastName(m.swingSide === 1 ? data.player1 : data.player2)}`}
          onEnter={() => showHighlight(m.swingX - 3, m.swingX + 2, m.swingSide === 1 ? colors.hl1 : colors.hl2)}
          onLeave={hideHighlight}
        />
      </div>
    </div>
  );
}

function AxisName({ name, color }: { name: string; color: string }) {
  return (
    <div style={{ flex: 1, display: "flex", alignItems: "center", justifyContent: "center" }}>
      <div style={{ writingMode: "vertical-rl", transform: "rotate(180deg)", color, fontWeight: 500, fontSize: 12 }}>
        {lastName(name)}
      </div>
    </div>
  );
}

function MetaCard({
  label,
  value,
  onEnter,
  onLeave,
}: {
  label: string;
  value: string;
  onEnter?: () => void;
  onLeave?: () => void;
}) {
  return (
    <div
      onMouseEnter={onEnter}
      onMouseLeave={onLeave}
      style={{ background: "var(--panel-2)", padding: "10px 12px", borderRadius: 8, fontSize: 12, cursor: "default" }}
    >
      <div className="muted">{label}</div>
      <div style={{ fontWeight: 500, fontSize: 14, marginTop: 2 }}>{value}</div>
    </div>
  );
}

/** "L. Samsonova" → "Samsonova"; leaves single-token names alone. */
function lastName(name: string): string {
  const parts = name.trim().split(/\s+/);
  return parts.length > 1 ? parts.slice(1).join(" ") : name;
}
