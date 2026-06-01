// Mirrors the backend DTOs.

export interface PlayerProfile {
  playerId: number;
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
  opponentId?: number;
  opponentName?: string;
  score?: string;
}

export interface H2h {
  playerId: number;
  opponentId: number;
  playerWins: number;
  opponentWins: number;
  matches: MatchDto[];
}

export interface PlayerSide {
  name: string;
  playerId?: number;
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
  player1: PlayerSide;
  player2: PlayerSide;
  score?: { home?: SideScore; away?: SideScore } | null;
  startTime?: string;
}

export interface SideScore {
  sets?: number[];
  point?: string;
  games?: number;
}

export interface RankingRow {
  rank: number;
  playerId?: number;
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
  playerId: number;
  firstName?: string;
  lastName?: string;
  tour?: string;
}

export interface AuthResponse {
  token: string;
  email: string;
  admin: boolean;
}
