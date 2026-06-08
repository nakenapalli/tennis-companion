"use client";

import useSWR from "swr";
import Link from "next/link";
import { fetcher } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { ScoresFeed } from "@/components/ScoresFeed";
import { Markdown } from "@/components/Markdown";
import type { Favorite, Insight, RankingRow } from "@/lib/types";

/**
 * Pull citations out of the digest body and collect them for a single muted footer — handling BOTH the new
 * trailing "Sources:" line and older inline "([Publication](url))" citations. Returns the cleaned body plus
 * the deduped sources (by url).
 */
function extractSources(markdown: string): { body: string; sources: Array<{ text: string; url: string }> } {
  const sources: Array<{ text: string; url: string }> = [];
  const seen = new Set<string>();
  for (const m of markdown.matchAll(/\[([^\]]+)\]\(([^)]+)\)/g)) {
    const url = m[2].trim();
    if (!seen.has(url)) {
      seen.add(url);
      sources.push({ text: m[1].trim(), url });
    }
  }
  const body = markdown
    .replace(/\n+\s*sources:[\s\S]*$/i, "") // a new-prompt trailing "Sources:" line
    .replace(/[ \t]*\((?:[^()]*\[[^\]]+\]\([^)]+\))+[^()]*\)/g, "") // older inline "(…[Pub](url)…)" citations
    .replace(/[ \t]+([.,;:])/g, "$1") // tidy any space left before punctuation
    .trimEnd();
  return { body, sources };
}

export default function HomePage() {
  const { token } = useAuth();
  const atp = useSWR<RankingRow[]>("/api/rankings?tour=ATP&limit=5", fetcher);
  const favs = useSWR<Favorite[]>(token ? "/api/me/favorites" : null, fetcher);
  // Latest published digest. Renders only if present — any generation/scrape/LLM failure means nothing
  // is published, so the home gracefully falls back to scores + rankings with no insight.
  const insight = useSWR<Insight | undefined>("/api/insights/latest?type=weekly_digest", fetcher);

  const scores = <ScoresFeed limit={4} moreHref="/scores" />;
  const digest = insight.data?.bodyMarkdown ? extractSources(insight.data.bodyMarkdown) : null;

  return (
    <div>
      {/* <h1>Welcome</h1>
      <p className="sub">What you missed and what&apos;s worth watching.</p> */}

      

      {/* Scores left, latest digest right. With no published digest, scores span the full width. */}
      {insight.data ? (
        <div className="home-split">
          <div>{scores}</div>
          <section>
            <h2>The Latest</h2>
            <p className="sub">{insight.data.title}</p>
            <article className="digest">
              <Markdown source={digest?.body ?? insight.data.bodyMarkdown} />
            </article>
            {digest && digest.sources.length > 0 && (
              <div className="digest-sources">
                Sources:{" "}
                {digest.sources.map((s, i) => (
                  <span key={s.url}>
                    {i > 0 ? ", " : ""}
                    <a href={s.url} target="_blank" rel="noopener noreferrer">{s.text}</a>
                  </span>
                ))}
              </div>
            )}
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
