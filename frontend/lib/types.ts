// Mirrors the backend DTOs.

export interface PlayerProfile {
  playerId: string;
  firstName?: string;
  lastName?: string;
  tour: string;
  country?: string;
  hand?: string;
  heightCm?: number;
  birthDate?: string;
  currentRank?: number;
  currentRankDate?: string;
}

export interface MatchDto {
  tourneyName?: string;
  tourneyDate?: string;
  surface?: string;
  round?: string;
  result: string; // "W" | "L"
  opponentId?: string;
  opponentName?: string;
  score?: string;
}

export interface H2h {
  playerId: string;
  opponentId: string;
  playerWins: number;
  opponentWins: number;
  matches: MatchDto[];
}

export interface PlayerSide {
  name: string;
  playerId?: string;
  country?: string;
  rank?: number;
}

export interface LiveMatch {
  externalId: string;
  status: string;
  tournamentName?: string;
  round?: string;
  surface?: string;
  tour?: string;
  category?: string; // ATP | WTA | Challenger | ITF | Junior | ...
  qualifying?: boolean; // qualifying-draw match — feed reuses main-draw round names, so this is the only marker
  player1: PlayerSide;
  player2: PlayerSide;
  score?: { home?: SideScore; away?: SideScore } | null;
  startTime?: string;
  tournamentId?: number; // canonical tournaments.id, name-matched on read — link target for the tournament page
  tier?: string; // GRAND_SLAM | FINALS | MASTERS_1000 | TOUR_500 | TOUR_250 | CHALLENGER | ITF | JUNIOR | OTHER
  serve?: string; // "home" | "away" — who is currently serving (live only)
}

export interface SideScore {
  sets?: number[];
  point?: string;
  games?: number;
}

export interface RankingRow {
  rank: number;
  playerId?: string;
  name: string;
  country?: string;
  points?: number;
}

export interface Tournament {
  id: number;
  externalId: string;
  name: string;
  level?: string;
  surface?: string;
  location?: string;
  tour?: string;
  startDate?: string;
  endDate?: string;
}

export interface Favorite {
  playerId: string;
  firstName?: string;
  lastName?: string;
  tour?: string;
}

export interface AuthResponse {
  token: string;
  email: string;
  username?: string;
  admin: boolean;
}

/** A single match for the dedicated match view (LiveMatch + an approximate end time when finished). */
export interface MatchDetail extends LiveMatch {
  endedAt?: string;
}

/** Momentum-tab payload (bespoke metric over the point-by-point flow). */
export interface MomentumPoint {
  x: number; // points played
  y: number; // signed momentum, + = player1, − = player2 (−1..1)
  sets: string; // completed-set games, e.g. "6-3"
  games: string; // current-set games "4-3"
  points: string; // in-game point score "30-15" ("" on a game-ending sample)
  server: number; // who is serving this game: 1 | 2 (0 on the origin sample)
}
export interface MomentumBreak {
  x: number;
  y: number;
  by: number; // 1 | 2
}
export interface MomentumSet {
  label: string;
  score: string;
  startX: number;
  endX: number;
}
export interface MomentumMeta {
  largestStreak: number;
  streakSide: number;
  streakStartX: number;
  streakEndX: number;
  heaviestGame: string;
  heaviestStartX: number;
  heaviestEndX: number;
  biggestSwing: number;
  swingSide: number;
  swingX: number;
}
export interface Momentum {
  bestOf: number;
  player1: string;
  player2: string;
  series: MomentumPoint[];
  breaks: MomentumBreak[];
  sets: MomentumSet[];
  meta: MomentumMeta;
}

/** Head-to-head tab payload. */
export interface H2hMeeting {
  date?: string;
  tournament?: string;
  round?: string;
  surface?: string;
  winner: number; // 1 | 2
  score?: string;
}
export interface H2hView {
  player1: string;
  player2: string;
  p1Wins: number;
  p2Wins: number;
  source: string; // "historical" | "live"
  meetings: H2hMeeting[];
}

/** Players tab payload: side-by-side bios. */
export interface PlayerBio {
  name: string;
  country?: string;
  hand?: string;
  heightCm?: number;
  age?: number;
  rank?: number;
  logo?: string;
  season?: string;
  titles?: number;
  wins?: number;
  losses?: number;
  hardWins?: number;
  hardLosses?: number;
  clayWins?: number;
  clayLosses?: number;
  grassWins?: number;
  grassLosses?: number;
}
export interface PlayersView {
  player1: PlayerBio;
  player2: PlayerBio;
}

/** Stats-tab payload: per-period (match/set1/…) groups of comparison rows. */
export interface StatCell {
  value: string | null;
  won: number | null;
  total: number | null;
}
export interface StatRow {
  name: string;
  p1: StatCell;
  p2: StatCell;
}
export interface StatGroup {
  type: string;
  rows: StatRow[];
}
export interface MatchStats {
  player1: string;
  player2: string;
  periods: string[];
  groups: Record<string, StatGroup[]>;
}

export interface ChatMessage {
  id: string;
  authorName: string;
  text: string;
  createdAt: string;
}

export interface ChatThreadSummary {
  id: string;
  title: string;
  authorName: string;
  createdAt: string;
  messageCount: number;
  activeChatters: number;
}

export interface ChatThreadDetail {
  id: string;
  title: string;
  authorName: string;
  createdAt: string;
  messages: ChatMessage[];
  locked: boolean;
}

export interface ThreadList {
  active: ChatThreadSummary[];
  latest: ChatThreadSummary[];
  locked: boolean;
}

/** A news headline on a tournament's Overview tab (metadata only — bodies are never persisted). */
export interface Headline {
  title: string;
  publication: string;
  url: string;
  publishedAt?: string;
}

/** A chat thread surfaced on a tournament's Threads tab, carrying its owning match for the condensed score. */
export interface TournamentThread {
  matchExternalId: string;
  threadId: string;
  title: string;
  authorName: string;
  messageCount: number;
  activeChatters: number;
  match: LiveMatch;
}

export interface Insight {
  id: number;
  type: string; // e.g. "weekly_digest"
  title: string;
  bodyMarkdown: string;
  generatedAt: string;
  publishedAt?: string;
}

/** An upstream player Tiers 0–3 couldn't confidently map — awaiting human review. */
export interface UnmappedEntity {
  source: string;
  externalPlayerId: string;
  externalName?: string;
  country?: string; // upstream IOC code — stored from the rankings feed or an enriched profile
  rankHint?: number; // upstream rank — stored from the rankings feed or an enriched profile
  birthYear?: number; // enriched from the upstream profile; persisted so repeat views are free
  confidence?: number;
  tier?: string;
  rationale?: string;
}

/** A canonical player offered as a possible mapping for an unmapped entity. */
export interface ReviewCandidate {
  playerId: string;
  sackmannId?: number;
  name: string;
  country?: string;
  birthYear?: number;
}

/** The upstream player's profile, fetched live in review to disambiguate namesakes. */
export interface UpstreamProfile {
  country?: string; // IOC code
  birthYear?: number;
  rank?: number;
}

/** One of the upstream player's recent results, shown in review to help recognize them. */
export interface UpstreamMatch {
  date?: string;
  tournamentName?: string;
  round?: string;
  opponentName?: string;
  result?: string; // "W" | "L"
  score?: string;
}
