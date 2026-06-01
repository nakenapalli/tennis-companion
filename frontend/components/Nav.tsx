"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useAuth } from "@/lib/auth";

const LINKS: [string, string][] = [
  ["/", "Home"],
  ["/scores", "Scores"],
  ["/rankings", "Rankings"],
  ["/tournaments", "Tournaments"],
];

export function Nav() {
  const { token, email, logout } = useAuth();
  const path = usePathname();
  return (
    <nav className="nav">
      <div className="nav-inner">
        <Link href="/" className="brand">🎾 Tennis Companion</Link>
        <div className="nav-links">
          {LINKS.map(([href, label]) => (
            <Link key={href} href={href} className={path === href ? "active" : ""}>
              {label}
            </Link>
          ))}
        </div>
        <div className="nav-auth">
          {token ? (
            <>
              <Link href="/settings">{email}</Link>
              <button onClick={logout} className="btn-link">Log out</button>
            </>
          ) : (
            <Link href="/login">Log in</Link>
          )}
        </div>
      </div>
    </nav>
  );
}
