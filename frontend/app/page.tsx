"use client";

import useSWR from "swr";
import Link from "next/link";
import { fetcher } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { ScoresFeed } from "@/components/ScoresFeed";
import { Markdown } from "@/components/Markdown";
import type { Favorite, Insight, RankingRow } from "@/lib/types";

export default function HomePage() {
  const { token } = useAuth();
  const atp = useSWR<RankingRow[]>("/api/rankings?tour=ATP&limit=5", fetcher);
  const favs = useSWR<Favorite[]>(token ? "/api/me/favorites" : null, fetcher);
  // Latest published digest. Renders only if present — any generation/scrape/LLM failure means nothing
  // is published, so the home gracefully falls back to scores + rankings with no insight.
  const insight = useSWR<Insight | undefined>("/api/insights/latest?type=weekly_digest", fetcher);

  const scores = <ScoresFeed limit={4} moreHref="/scores" />;

  return (
    <div>
      {/* <h1>Welcome</h1>
      <p className="sub">What you missed and what&apos;s worth watching.</p> */}

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

      {/* Scores left, latest digest right. With no published digest, scores span the full width. */}
      {insight.data ? (
        <div className="home-split">
          <div>{scores}</div>
          <section>
            <h2>The Latest</h2>
            <p className="sub">{insight.data.title}</p>
            <article className="digest">
              <Markdown source={insight.data.bodyMarkdown} />
            </article>
          </section>
        </div>
      ) : (
        scores
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
