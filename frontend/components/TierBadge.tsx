import type { ReactElement } from "react";

/**
 * Colored chip for a tournament's tier (server-computed `tier`). Grand Slam = gold, 1000 = silver, lower
 * tiers shade through blues; unknown/OTHER renders nothing.
 *
 * `detailed` (home cards) prefixes the tour — "ATP 500", "WTA 1000", "ATP Finals". Plain (section headers
 * on /scores) is just the tier — "Grand Slam", "1000", "500".
 */
const TIER_CLS: Record<string, string> = {
  GRAND_SLAM: "tier-slam",
  FINALS: "tier-finals",
  MASTERS_1000: "tier-1000",
  TOUR_500: "tier-500",
  TOUR_250: "tier-250",
  CHALLENGER: "tier-challenger",
  ITF: "tier-itf",
  JUNIOR: "tier-junior",
};

const PLAIN: Record<string, string> = {
  GRAND_SLAM: "Grand Slam",
  FINALS: "Finals",
  MASTERS_1000: "1000",
  TOUR_500: "500",
  TOUR_250: "250",
  CHALLENGER: "Challenger",
  ITF: "ITF",
  JUNIOR: "Junior",
};

function detailedLabel(tier: string, tour?: string): string {
  const prefix = tour ? `${tour} ` : "";
  switch (tier) {
    case "FINALS":
      return `${prefix}Finals`;
    case "MASTERS_1000":
      return `${prefix}1000`;
    case "TOUR_500":
      return `${prefix}500`;
    case "TOUR_250":
      return `${prefix}250`;
    default:
      return PLAIN[tier] ?? ""; // Grand Slam / Challenger / ITF / Junior: no tour prefix
  }
}

export function TierBadge({
  tier,
  tour,
  detailed = false,
}: {
  tier?: string;
  tour?: string;
  detailed?: boolean;
}): ReactElement | null {
  const cls = tier ? TIER_CLS[tier] : undefined;
  if (!tier || !cls) return null;
  const label = detailed ? detailedLabel(tier, tour) : PLAIN[tier];
  return <span className={`tier-badge ${cls}`}>{label}</span>;
}
