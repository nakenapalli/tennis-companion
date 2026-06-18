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

export interface Insight {
  id: number;
  type: string; // e.g. "weekly_digest"
  title: string;
  bodyMarkdown: string;
  generatedAt: string;
  publishedAt?: string;
}
