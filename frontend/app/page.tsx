"use client";

import useSWR from "swr";
import Link from "next/link";
import { fetcher } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { MatchCard } from "@/components/MatchCard";
import type { Favorite, LiveMatch, RankingRow } from "@/lib/types";

export default function HomePage() {
  const { token } = useAuth();
  const live = useSWR<LiveMatch[]>("/api/scores/live", fetcher, { refreshInterval: 30000 });
  const atp = useSWR<RankingRow[]>("/api/rankings?tour=ATP&limit=5", fetcher);
  const favs = useSWR<Favorite[]>(token ? "/api/me/favorites" : null, fetcher);

  return (
    <div>
      <h1>Welcome</h1>
      <p className="sub">What you missed and what&apos;s worth watching.</p>

      {token && favs.data && favs.data.length > 0 && (
        <>
          <h2>Your favorites</h2>
          <div className="grid">
            {favs.data.map((f) => (
              <Link key={f.playerId} href={`/players/${f.playerId}`} className="card card-link">
                <strong>{f.firstName} {f.lastName}</strong>
                <div className="muted">{f.tour}</div>
              </Link>
            ))}
          </div>
        </>
      )}

      <div className="row-between">
        <h2>Live now</h2>
        <Link href="/scores" className="player-link">All scores →</Link>
      </div>
      {live.data && live.data.length > 0 ? (
        <div className="grid">
          {live.data.slice(0, 4).map((m) => (
            <MatchCard key={m.externalId} m={m} />
          ))}
        </div>
      ) : (
        <div className="empty">No live matches right now.</div>
      )}

      <div className="row-between">
        <h2>ATP top 5</h2>
        <Link href="/rankings" className="player-link">Full rankings →</Link>
      </div>
      {atp.data && atp.data.length > 0 ? (
        <table>
          <tbody>
            {atp.data.map((r) => (
              <tr key={r.rank}>
                <td className="rank-num">{r.rank}</td>
                <td>
                  {r.playerId ? (
                    <Link href={`/players/${r.playerId}`} className="player-link">{r.name}</Link>
                  ) : (
                    r.name
                  )}
                </td>
                <td className="muted">{r.country}</td>
                <td>{r.points}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <div className="empty">Rankings not loaded yet.</div>
      )}
    </div>
  );
}
