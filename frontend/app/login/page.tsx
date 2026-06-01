"use client";

import { useState, type FormEvent } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth";

export default function LoginPage() {
  const { login, register } = useAuth();
  const router = useRouter();
  const [mode, setMode] = useState<"login" | "register">("login");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      if (mode === "login") await login(email, password);
      else await register(email, password);
      router.push("/");
    } catch {
      setError(mode === "login" ? "Invalid credentials." : "Could not register (email may be taken, or password < 8 chars).");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div>
      <h1>{mode === "login" ? "Log in" : "Create account"}</h1>
      <p className="sub">Personalize your home screen and follow players.</p>
      <form className="form" onSubmit={submit}>
        <input type="email" placeholder="Email" value={email} onChange={(e) => setEmail(e.target.value)} required />
        <input
          type="password"
          placeholder="Password (at least 8 characters)"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          required
        />
        {error && <div className="error">{error}</div>}
        <button className="btn" disabled={busy}>{busy ? "…" : mode === "login" ? "Log in" : "Register"}</button>
        <button type="button" className="btn-link" onClick={() => setMode(mode === "login" ? "register" : "login")}>
          {mode === "login" ? "Need an account? Register" : "Have an account? Log in"}
        </button>
      </form>
    </div>
  );
}
