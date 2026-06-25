/** Where the user came from, carried into the match view so its back button can return there. */
export interface BackContext {
  label: string; // e.g. "Scores", "Wimbledon", "Wimbledon - ATP"
  href: string; // an internal path, optionally with a query (e.g. "/tournaments/42?tour=ATP")
}

/**
 * Build the match-view URL, stashing the origin (`b`/`bl`) so the back button is dynamic. `extra` carries
 * any other params the caller needs (e.g. a `thread` deep-link from a tournament's Threads tab).
 */
export function matchHref(externalId: string, back?: BackContext, extra?: Record<string, string>): string {
  const params = new URLSearchParams(extra);
  if (back) {
    params.set("b", back.href);
    params.set("bl", back.label);
  }
  const qs = params.toString();
  return `/matches/${encodeURIComponent(externalId)}${qs ? `?${qs}` : ""}`;
}
