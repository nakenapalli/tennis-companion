"use client";

import { ScoresFeed } from "@/components/ScoresFeed";

export default function ScoresPage() {
  return (
    <div>
      <h1>Scores</h1>
      <p className="sub">Live matches when play is on, otherwise recently completed matches.</p>
      <ScoresFeed />
    </div>
  );
}
