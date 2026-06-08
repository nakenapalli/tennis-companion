"use client";

import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import { apiFetch, isJwtExpired } from "./api";
import type { AuthResponse } from "./types";

interface AuthState {
  token: string | null;
  email: string | null;
  username: string | null;
  admin: boolean;
}

interface AuthContextValue extends AuthState {
  ready: boolean;
  login: (email: string, password: string) => Promise<void>;
  register: (email: string, username: string, password: string) => Promise<void>;
  logout: () => void;
}

const EMPTY: AuthState = { token: null, email: null, username: null, admin: false };

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>(EMPTY);
  const [ready, setReady] = useState(false);

  const clearSession = () => {
    localStorage.removeItem("token");
    localStorage.removeItem("email");
    localStorage.removeItem("username");
    localStorage.removeItem("admin");
    setState(EMPTY);
  };

  // hydrate from localStorage on mount (client only)
  useEffect(() => {
    const token = localStorage.getItem("token");
    if (token && !isJwtExpired(token)) {
      setState({
        token,
        email: localStorage.getItem("email"),
        username: localStorage.getItem("username"),
        admin: localStorage.getItem("admin") === "true",
      });
    } else if (token) {
      clearSession(); // expired token -> don't hydrate it, or it would break even public pages
    }
    setReady(true);
  }, []);

  // apiFetch broadcasts this when it discards a stale token (expired client-side or 401'd by the
  // server); mirror that into React state so the nav drops to logged-out without a manual reload.
  useEffect(() => {
    const onExpired = () => setState(EMPTY);
    window.addEventListener("auth:expired", onExpired);
    return () => window.removeEventListener("auth:expired", onExpired);
  }, []);

  const apply = (r: AuthResponse) => {
    localStorage.setItem("token", r.token);
    localStorage.setItem("email", r.email);
    if (r.username) localStorage.setItem("username", r.username);
    localStorage.setItem("admin", String(r.admin));
    setState({ token: r.token, email: r.email, username: r.username ?? null, admin: r.admin });
  };

  const value: AuthContextValue = {
    ...state,
    ready,
    login: async (email, password) =>
      apply(await apiFetch<AuthResponse>("/api/auth/login", { method: "POST", body: JSON.stringify({ email, password }) })),
    register: async (email, username, password) =>
      apply(await apiFetch<AuthResponse>("/api/auth/register", { method: "POST", body: JSON.stringify({ email, username, password }) })),
    logout: clearSession,
  };

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used within AuthProvider");
  return ctx;
}
