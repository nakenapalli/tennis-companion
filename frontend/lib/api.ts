// Thin API client. Base URL is configurable; the JWT (if present) is attached automatically.

const BASE = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message);
  }
}

/**
 * True if the JWT is structurally unusable or past its `exp`. We decode (not verify — that's the
 * server's job) only to avoid attaching a token we already know the server will reject with 401.
 * A malformed token counts as expired so we discard it rather than send garbage.
 */
export function isJwtExpired(jwt: string): boolean {
  try {
    const payload = jwt.split(".")[1];
    if (!payload) return true;
    const { exp } = JSON.parse(atob(payload.replace(/-/g, "+").replace(/_/g, "/")));
    if (typeof exp !== "number") return false; // no exp claim -> treat as non-expiring
    return exp * 1000 <= Date.now();
  } catch {
    return true;
  }
}

/** Drop stored auth and notify the app (AuthProvider listens) so React state falls back to anonymous. */
export function clearAuth(): void {
  if (typeof window === "undefined") return;
  localStorage.removeItem("token");
  localStorage.removeItem("email");
  localStorage.removeItem("admin");
  window.dispatchEvent(new Event("auth:expired"));
}

function token(): string | null {
  if (typeof window === "undefined") return null;
  const t = localStorage.getItem("token");
  if (t && isJwtExpired(t)) {
    // Never attach a known-expired token: it would turn public 200s into 401s. Self-heal instead.
    clearAuth();
    return null;
  }
  return t;
}

export async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const t = token();
  const res = await fetch(`${BASE}${path}`, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      ...(t ? { Authorization: `Bearer ${t}` } : {}),
      ...(init?.headers ?? {}),
    },
  });
  if (!res.ok) {
    // A 401 on a request we authenticated means the token is stale/revoked server-side: drop it so
    // subsequent (incl. public) requests retry anonymously and succeed, instead of failing forever.
    if (res.status === 401 && t) clearAuth();
    throw new ApiError(res.status, await res.text().catch(() => res.statusText));
  }
  if (res.status === 204) return undefined as T;
  return res.json() as Promise<T>;
}

/** SWR fetcher — pass the path as the SWR key. */
export const fetcher = <T>(path: string): Promise<T> => apiFetch<T>(path);
