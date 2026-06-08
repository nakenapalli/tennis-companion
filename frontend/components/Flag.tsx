import { flagEmoji } from "@/lib/flags";

/** Country flag from an IOC code; falls back to the code text when the nation isn't in the map. */
export function Flag({ ioc }: { ioc?: string | null }) {
  if (!ioc) return null;
  const emoji = flagEmoji(ioc);
  return emoji ? (
    <span className="flag" title={ioc} aria-label={ioc}>{emoji}</span>
  ) : (
    <span className="flag flag-code" title={ioc}>{ioc}</span>
  );
}
