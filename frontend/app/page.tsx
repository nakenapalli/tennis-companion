"use client";

import useSWR from "swr";
import Link from "next/link";
import { fetcher } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { ScoresFeed } from "@/components/ScoresFeed";
import { Markdown } from "@/components/Markdown";
import { Flag } from "@/components/Flag";
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
    const text = m[1].trim();
    const url = m[2].trim();
    // Dedupe by publication name (the displayed text), so the same source isn't shown twice even when
    // it's cited via two different article URLs.
    const key = text.toLowerCase();
    if (!seen.has(key)) {
      seen.add(key);
      sources.push({ text, url });
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
  const wta = useSWR<RankingRow[]>("/api/rankings?tour=WTA&limit=5", fetcher);
  const favs = useSWR<Favorite[]>(token ? "/api/me/favorites" : null, fetcher);
  // Latest published digest. Renders only if present — any generation/scrape/LLM failure means nothing
  // is published, so the home gracefully falls back to scores + rankings with no insight.
  const insight = useSWR<Insight | undefined>("/api/insights/latest?type=weekly_digest", fetcher);

  const digest = insight.data?.bodyMarkdown ? extractSources(insight.data.bodyMarkdown) : null;

  const scores = <ScoresFeed tourColumns moreHref="/scores" back={{ label: "Home", href: "/" }} />;

  return (
    <div>
      {/* Scores (ATP/WTA columns) on the left, latest digest on the right. With no published digest, the
          scores span the full width. The top-5 lists follow underneath. */}
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

      <div className="rankings-split" style={{ marginTop: 36 }}>
        <Top5 title="ATP top 5" tour="ATP" rows={atp.data} />
        <Top5 title="WTA top 5" tour="WTA" rows={wta.data} />
      </div>
    </div>
  );
}

/** A tour's top-5 ranking table with a heading + link to that tour's full rankings. */
function Top5({ title, tour, rows }: { title: string; tour: "ATP" | "WTA"; rows?: RankingRow[] }) {
  return (
    <section>
      <div className="row-between">
        <h2>{title}</h2>
        <Link href={`/rankings?tour=${tour}`} className="player-link">See all →</Link>
      </div>
      {rows && rows.length > 0 ? (
        <table>
          <tbody>
            {rows.map((r) => (
              <tr key={r.rank}>
                <td className="rank-num">{r.rank}</td>
                <td>
                  <Flag ioc={r.country} />{" "}
                  {r.playerId ? (
                    <Link href={`/players/${r.playerId}`} className="player-link">{r.name}</Link>
                  ) : (
                    r.name
                  )}
                </td>
                <td>{r.points}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <div className="empty">Rankings not loaded yet.</div>
      )}
    </section>
  );
}
