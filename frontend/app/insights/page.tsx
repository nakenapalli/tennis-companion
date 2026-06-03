"use client";

import useSWR from "swr";
import { fetcher } from "@/lib/api";
import { Markdown } from "@/components/Markdown";
import type { Insight } from "@/lib/types";

export default function InsightsPage() {
  // /api/insights/latest returns 204 No Content (-> undefined) when nothing is published yet.
  const { data, error, isLoading } = useSWR<Insight | undefined>(
    "/api/insights/latest?type=weekly_digest",
    fetcher,
  );

  return (
    <div>
      <h1>{data?.title ?? "What's Worth Watching"}</h1>
      <p className="sub">
        {data
          ? `A fact-checked weekly roundup · generated ${new Date(data.generatedAt).toLocaleDateString()}`
          : "A weekly, fact-checked roundup of the tennis worth your time."}
      </p>

      {isLoading ? (
        <div className="spinner">Loading…</div>
      ) : error ? (
        <div className="empty">Couldn&apos;t load the digest right now.</div>
      ) : !data ? (
        <div className="empty">No digest published yet — check back after the weekly roundup.</div>
      ) : (
        <article className="digest">
          <Markdown source={data.bodyMarkdown} />
        </article>
      )}
    </div>
  );
}
