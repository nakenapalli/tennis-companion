"use client";

import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import { apiFetch } from "./api";
import type { AuthResponse } from "./types";

interface AuthState {
  token: string | null;
  email: string | null;
  admin: boolean;
}

interface AuthContextValue extends AuthState {
  ready: boolean;
  login: (email: string, password: string) => Promise<void>;
  register: (email: string, password: string) => Promise<void>;
  logout: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>({ token: null, email: null, admin: false });
  const [ready, setReady] = useState(false);

  // hydrate from localStorage on mount (client only)
  useEffect(() => {
    const token = localStorage.getItem("token");
    const email = localStorage.getItem("email");
    const admin = localStorage.getItem("admin") === "true";
    if (token) setState({ token, email, admin });
    setReady(true);
  }, []);

  const apply = (r: AuthResponse) => {
    localStorage.setItem("token", r.token);
    localStorage.setItem("email", r.email);
    localStorage.setItem("admin", String(r.admin));
    setState({ token: r.token, email: r.email, admin: r.admin });
  };

  const value: AuthContextValue = {
    ...state,
    ready,
    login: async (email, password) =>
      apply(await apiFetch<AuthResponse>("/api/auth/login", { method: "POST", body: JSON.stringify({ email, password }) })),
    register: async (email, password) =>
      apply(await apiFetch<AuthResponse>("/api/auth/register", { method: "POST", body: JSON.stringify({ email, password }) })),
    logout: () => {
      localStorage.removeItem("token");
      localStorage.removeItem("email");
      localStorage.removeItem("admin");
      setState({ token: null, email: null, admin: false });
    },
  };

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used within AuthProvider");
  return ctx;
}
