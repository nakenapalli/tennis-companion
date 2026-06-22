"use client";

import useSWR from "swr";
import { fetcher } from "@/lib/api";
import { Flag } from "@/components/Flag";
import type { PlayerColors } from "@/lib/playerColors";
import type { PlayerBio, PlayersView } from "@/lib/types";

/** The Players tab: side-by-side bios — DB profile (hand/height/age/rank) + live career splits. */
export function MatchPlayers({ externalId, live, colors }: { externalId: string; live: boolean; colors: PlayerColors }) {
  const { data, error, isLoading } = useSWR<PlayersView>(
    `/api/matches/${externalId}/players`,
    fetcher,
    { refreshInterval: live ? 60000 : 0, shouldRetryOnError: false },
  );

  if (isLoading) return <div className="spinner">Loading players…</div>;
  if (error || !data) return <div className="empty">Player info isn&apos;t available for this match.</div>;

  return (
    <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(220px, 1fr))", gap: 12 }}>
      <BioCard bio={data.player1} accent={colors.c1} />
      <BioCard bio={data.player2} accent={colors.c2} />
    </div>
  );
}

function BioCard({ bio, accent }: { bio: PlayerBio; accent: string }) {
  const hand = bio.hand === "R" ? "Right-handed" : bio.hand === "L" ? "Left-handed" : bio.hand ?? undefined;
  return (
    <div className="card" style={{ borderTop: `2px solid ${accent}`, borderTopLeftRadius: 0, borderTopRightRadius: 0 }}>
      <div style={{ display: "flex", alignItems: "center", gap: 10, marginBottom: 12 }}>
        {bio.logo && <img src={bio.logo} alt="" width={40} height={40} style={{ borderRadius: "50%", objectFit: "cover" }} />}
        <div style={{ minWidth: 0 }}>
          <div style={{ fontWeight: 500 }}>{bio.name} <Flag ioc={bio.country} /></div>
          {bio.rank != null && <div className="muted" style={{ fontSize: 12 }}>Rank #{bio.rank}</div>}
        </div>
      </div>

      <Row label="Age" value={bio.age != null ? String(bio.age) : "Unknown"} />
      <Row label="Height" value={bio.heightCm ? `${bio.heightCm} cm` : "Unknown"} />
      <Row label="Hand" value={bio.hand ?? "Unknown" }/>

      {(bio.wins != null || bio.titles != null) && (
        <div style={{ marginTop: 10, paddingTop: 10, borderTop: "1px solid var(--line)" }}>
          <div className="muted" style={{ fontSize: 11, textTransform: "uppercase", letterSpacing: "0.05em", marginBottom: 6 }}>
            {bio.season ? `${bio.season} season` : "Season"}
          </div>
          <Row label="Win–loss" value={record(bio.wins, bio.losses)} />
          <Row label="Titles" value={bio.titles != null ? String(bio.titles) : undefined} />
          <Row label="Hard" value={record(bio.hardWins, bio.hardLosses)} />
          <Row label="Clay" value={record(bio.clayWins, bio.clayLosses)} />
          <Row label="Grass" value={record(bio.grassWins, bio.grassLosses)} />
        </div>
      )}
    </div>
  );
}

function Row({ label, value }: { label: string; value?: string }) {
  if (!value) return null;
  return (
    <div style={{ display: "flex", justifyContent: "space-between", fontSize: 13, padding: "2px 0" }}>
      <span className="muted">{label}</span>
      <span style={{ fontVariantNumeric: "tabular-nums" }}>{value}</span>
    </div>
  );
}

/** "30–10", or undefined when both are missing/zero-length. */
function record(w?: number, l?: number): string | undefined {
  if (w == null && l == null) return undefined;
  return `${w ?? 0}–${l ?? 0}`;
}
