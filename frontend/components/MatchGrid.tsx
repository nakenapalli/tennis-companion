"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import type { BackContext } from "@/lib/matchHref";
import type { LiveMatch } from "@/lib/types";
import { MatchCard } from "@/components/MatchCard";

const ROWS = 2; // show at most this many full rows of cards before "See more"
const FALLBACK_COLS = 4; // assume a full-width 4-column grid until measured — avoids a first-paint flash on desktop

/**
 * Count the columns the auto-fill grid actually laid out. The browser resolves `repeat(auto-fill, …)` to a
 * concrete track list, so this works even when the grid is empty (the track count depends on width, not item
 * count) — which is what lets us cap the visible cards at N rows responsively.
 */
function countColumns(el: HTMLElement): number {
  const tpl = getComputedStyle(el).gridTemplateColumns;
  if (!tpl || tpl === "none") return 0;
  return tpl.split(" ").filter(Boolean).length;
}

/**
 * A `.grid` of match cards capped at two full rows: it measures the rendered column count and shows
 * `columns × 2` cards, re-measuring on resize. `seeMoreHref` adds a link past the cap — shown only when
 * cards are actually hidden, unless `seeMoreAlways` (the /scores sections always link into the tournament).
 */
export function MatchGrid({
  matches,
  grouped = false,
  back,
  seeMoreHref,
  seeMoreAlways = false,
  seeMoreLabel,
}: {
  matches: LiveMatch[];
  grouped?: boolean;
  back?: BackContext;
  seeMoreHref?: string | null;
  seeMoreAlways?: boolean;
  seeMoreLabel?: (total: number) => string;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const [cols, setCols] = useState(0);

  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const measure = () => setCols(countColumns(el));
    measure();
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  const limit = (cols || FALLBACK_COLS) * ROWS;
  const shown = matches.slice(0, limit);
  const hidden = matches.length - shown.length;
  const showSeeMore = !!seeMoreHref && (seeMoreAlways || hidden > 0);

  return (
    <>
      <div className="grid" ref={ref}>
        {shown.map((m) => (
          <MatchCard key={m.externalId} m={m} grouped={grouped} back={back} />
        ))}
      </div>
      {showSeeMore && (
        <div className="see-more-row">
          <Link href={seeMoreHref!} className="player-link">
            {seeMoreLabel ? seeMoreLabel(matches.length) : "See more →"}
          </Link>
        </div>
      )}
    </>
  );
}
