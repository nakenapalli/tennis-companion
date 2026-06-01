import type { Metadata } from "next";
import "./globals.css";
import { AuthProvider } from "@/lib/auth";
import { Nav } from "@/components/Nav";

export const metadata: Metadata = {
  title: "Tennis Companion",
  description: "Learn about and enjoy tennis — scores, rankings, players and tournaments.",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>
        <AuthProvider>
          <Nav />
          <main className="container">{children}</main>
          <footer className="attribution">
            Historical data derived from Jeff Sackmann / Tennis Abstract datasets (CC BY-NC-SA 4.0).
            Live data via TennisApi (RapidAPI). Non-commercial / portfolio demo.
          </footer>
        </AuthProvider>
      </body>
    </html>
  );
}
