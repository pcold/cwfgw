# CWFGW — Castlewood Fantasy Golf Backend Specification

Complete specification for reimplementing the backend API layer in any language/framework.

---

## Table of Contents

1. [Overview](#overview)
2. [Architecture](#architecture)
3. [Database Schema](#database-schema)
4. [Domain Models](#domain-models)
5. [API Endpoints](#api-endpoints)
6. [Business Logic](#business-logic)
7. [ESPN Integration](#espn-integration)
8. [Authentication](#authentication)
9. [Infrastructure](#infrastructure)

---

## Overview

Fantasy golf league tracker for a local group ("Castlewood"). Teams draft PGA golfers; when those golfers finish in the top 10 of real PGA tournaments, the team earns money. Earnings are zero-sum across teams each week. Side bets add a secondary competition layer based on per-round draft pick performance.

### Key Concepts

- **League** → has many **Seasons** → each has **Teams**, **Tournaments**, a **Draft**
- **Teams** roster **Golfers** via draft picks (8 rounds, snake order)
- **Tournaments** are finalized chronologically; finalization imports ESPN results and computes scores
- **Reports** show per-team earnings grids (8 rows × N teams), cumulative standings, side bets
- **Live overlay** merges ESPN in-progress data onto finalized reports without persisting

---

## Architecture

### Components

| Component | Responsibility |
|---|---|
| **HTTP Routes** | REST API, JSON request/response, auth middleware |
| **Services** | Business logic, orchestration, pure calculations |
| **Repositories** | Database access (SQL queries) |
| **ESPN Client** | HTTP client for ESPN scoreboard API |
| **Config** | Environment-based configuration |

### External Dependencies

- **PostgreSQL** — primary data store
- **ESPN Scoreboard API** — tournament results and live leaderboards (`site.api.espn.com`)
- **Flyway** — database migrations
- **BCrypt** — password hashing

### Key Constraints

- All financial calculations use precise decimal types (no floating point)
- Tournament finalization is serialized (mutex/semaphore) to prevent concurrent corruption
- Tournaments must be finalized in chronological order within a season
- Live overlay is read-only; it never persists projected data

---

## Database Schema

### Tables

#### `leagues`

| Column | Type | Constraints |
|---|---|---|
| `id` | UUID | PK, default `gen_random_uuid()` |
| `name` | TEXT | NOT NULL |
| `created_at` | TIMESTAMPTZ | NOT NULL, default `now()` |

#### `seasons`

| Column | Type | Constraints |
|---|---|---|
| `id` | UUID | PK, default `gen_random_uuid()` |
| `league_id` | UUID | NOT NULL, FK → `leagues(id)` |
| `name` | TEXT | NOT NULL |
| `season_year` | INT | NOT NULL |
| `season_number` | INT | NOT NULL, default 1 |
| `status` | TEXT | NOT NULL, default `'draft'` |
| `tie_floor` | NUMERIC(10,4) | NOT NULL, default 1 |
| `side_bet_amount` | NUMERIC(10,4) | NOT NULL, default 15 |
| `max_teams` | INT | NOT NULL, default 10 |
| `created_at` | TIMESTAMPTZ | NOT NULL, default `now()` |
| `updated_at` | TIMESTAMPTZ | NOT NULL, default `now()` |

Index: `idx_seasons_year` on `(season_year)`

#### `season_rule_payouts`

| Column | Type | Constraints |
|---|---|---|
| `id` | UUID | PK |
| `season_id` | UUID | NOT NULL, FK → `seasons(id)` ON DELETE CASCADE |
| `position` | INTEGER | NOT NULL |
| `amount` | NUMERIC(10,4) | NOT NULL |

Unique: `(season_id, position)`

Default payouts by position: `$18, $12, $10, $8, $7, $6, $5, $4, $3, $2`

#### `season_rule_side_bet_rounds`

| Column | Type | Constraints |
|---|---|---|
| `id` | UUID | PK |
| `season_id` | UUID | NOT NULL, FK → `seasons(id)` ON DELETE CASCADE |
| `round` | INTEGER | NOT NULL |

Unique: `(season_id, round)`

Default side bet rounds: `5, 6, 7, 8`

#### `golfers`

| Column | Type | Constraints |
|---|---|---|
| `id` | UUID | PK, default `gen_random_uuid()` |
| `pga_player_id` | TEXT | UNIQUE (nullable — ESPN athlete ID) |
| `first_name` | TEXT | NOT NULL |
| `last_name` | TEXT | NOT NULL |
| `country` | TEXT | |
| `world_ranking` | INT | |
| `active` | BOOLEAN | NOT NULL, default true |
| `updated_at` | TIMESTAMPTZ | NOT NULL, default `now()` |

Indexes: `idx_golfers_ranking` on `(world_ranking)`, `idx_golfers_name` on `(last_name, first_name)`

#### `tournaments`

| Column | Type | Constraints |
|---|---|---|
| `id` | UUID | PK, default `gen_random_uuid()` |
| `pga_tournament_id` | TEXT | UNIQUE (nullable — ESPN event ID) |
| `name` | TEXT | NOT NULL |
| `season_id` | UUID | FK → `seasons(id)` |
| `start_date` | DATE | NOT NULL |
| `end_date` | DATE | NOT NULL |
| `course_name` | TEXT | |
| `status` | TEXT | NOT NULL, default `'upcoming'` |
| `purse_amount` | BIGINT | |
| `payout_multiplier` | NUMERIC(6,4) | NOT NULL, default 1.00 |
| `week` | TEXT | |
| `created_at` | TIMESTAMPTZ | NOT NULL, default `now()` |

Status values: `upcoming`, `in_progress`, `completed`

Indexes: `idx_tournaments_season` on `(season_id)`, `idx_tournaments_dates` on `(start_date, end_date)`

#### `tournament_results`

| Column | Type | Constraints |
|---|---|---|
| `id` | UUID | PK |
| `tournament_id` | UUID | NOT NULL, FK → `tournaments(id)` |
| `golfer_id` | UUID | NOT NULL, FK → `golfers(id)` |
| `position` | INT | |
| `score_to_par` | INT | |
| `total_strokes` | INT | |
| `earnings` | BIGINT | |
| `round1` | INTEGER | |
| `round2` | INTEGER | |
| `round3` | INTEGER | |
| `round4` | INTEGER | |
| `made_cut` | BOOLEAN | NOT NULL, default true |

Unique: `(tournament_id, golfer_id)`

Indexes: `idx_results_tournament`, `idx_results_golfer`

#### `teams`

| Column | Type | Constraints |
|---|---|---|
| `id` | UUID | PK, default `gen_random_uuid()` |
| `season_id` | UUID | NOT NULL, FK → `seasons(id)` |
| `owner_name` | TEXT | NOT NULL |
| `team_name` | TEXT | NOT NULL |
| `team_number` | INT | |
| `created_at` | TIMESTAMPTZ | NOT NULL, default `now()` |
| `updated_at` | TIMESTAMPTZ | NOT NULL, default `now()` |

Unique: `(season_id, team_name)`

Index: `idx_teams_season`

#### `team_rosters`

| Column | Type | Constraints |
|---|---|---|
| `id` | UUID | PK |
| `team_id` | UUID | NOT NULL, FK → `teams(id)` |
| `golfer_id` | UUID | NOT NULL, FK → `golfers(id)` |
| `acquired_via` | TEXT | NOT NULL, default `'draft'` |
| `draft_round` | INT | |
| `ownership_pct` | NUMERIC(7,4) | NOT NULL, default 100.00 |
| `acquired_at` | TIMESTAMPTZ | NOT NULL, default `now()` |
| `dropped_at` | TIMESTAMPTZ | |
| `is_active` | BOOLEAN | NOT NULL, default true |

Unique: `(team_id, golfer_id, acquired_at)`

Partial indexes: `idx_roster_team_active` WHERE `dropped_at IS NULL`, `idx_roster_golfer_active` WHERE `dropped_at IS NULL`

#### `drafts`

| Column | Type | Constraints |
|---|---|---|
| `id` | UUID | PK |
| `season_id` | UUID | NOT NULL, FK → `seasons(id)`, UNIQUE |
| `status` | TEXT | NOT NULL, default `'pending'` |
| `draft_type` | TEXT | NOT NULL, default `'snake'` |
| `started_at` | TIMESTAMPTZ | |
| `completed_at` | TIMESTAMPTZ | |
| `created_at` | TIMESTAMPTZ | NOT NULL, default `now()` |

Status values: `pending`, `in_progress`, `completed`

#### `draft_picks`

| Column | Type | Constraints |
|---|---|---|
| `id` | UUID | PK |
| `draft_id` | UUID | NOT NULL, FK → `drafts(id)` |
| `team_id` | UUID | NOT NULL, FK → `teams(id)` |
| `golfer_id` | UUID | FK → `golfers(id)` (nullable until picked) |
| `round_num` | INT | NOT NULL |
| `pick_num` | INT | NOT NULL |
| `picked_at` | TIMESTAMPTZ | |

Unique: `(draft_id, pick_num)`

Indexes: `idx_picks_draft`, `idx_picks_team`

#### `fantasy_scores`

| Column | Type | Constraints |
|---|---|---|
| `id` | UUID | PK |
| `season_id` | UUID | NOT NULL, FK → `seasons(id)` |
| `team_id` | UUID | NOT NULL, FK → `teams(id)` |
| `tournament_id` | UUID | NOT NULL, FK → `tournaments(id)` |
| `golfer_id` | UUID | NOT NULL, FK → `golfers(id)` |
| `points` | NUMERIC(12,4) | NOT NULL, default 0 |
| `position` | INTEGER | NOT NULL, default 0 |
| `num_tied` | INTEGER | NOT NULL, default 1 |
| `base_payout` | NUMERIC(10,4) | NOT NULL, default 0 |
| `ownership_pct` | NUMERIC(10,4) | NOT NULL, default 100 |
| `payout` | NUMERIC(10,4) | NOT NULL, default 0 |
| `multiplier` | NUMERIC(4,2) | NOT NULL, default 1 |
| `calculated_at` | TIMESTAMPTZ | NOT NULL, default `now()` |

Unique: `(season_id, team_id, tournament_id, golfer_id)`

Indexes: `idx_scores_season_tournament`, `idx_scores_team`

#### `season_standings`

| Column | Type | Constraints |
|---|---|---|
| `id` | UUID | PK |
| `season_id` | UUID | NOT NULL, FK → `seasons(id)` |
| `team_id` | UUID | NOT NULL, FK → `teams(id)` |
| `total_points` | NUMERIC(14,4) | NOT NULL, default 0 |
| `tournaments_played` | INT | NOT NULL, default 0 |
| `last_updated` | TIMESTAMPTZ | NOT NULL, default `now()` |

Unique: `(season_id, team_id)`

#### `users`

| Column | Type | Constraints |
|---|---|---|
| `id` | UUID | PK |
| `username` | VARCHAR(50) | NOT NULL, UNIQUE |
| `password_hash` | VARCHAR(255) | NOT NULL |
| `role` | VARCHAR(20) | NOT NULL, default `'admin'` |
| `created_at` | TIMESTAMP | NOT NULL, default `now()` |

---

## Domain Models

All JSON uses **snake_case** field names. All IDs are UUID strings. All monetary values are decimal numbers (not floats).

### Core Entities

```
League {
  id: UUID, name: string, created_at: datetime
}

Season {
  id: UUID, league_id: UUID, name: string, season_year: int,
  season_number: int, status: string, tie_floor: decimal,
  side_bet_amount: decimal, max_teams: int,
  created_at: datetime, updated_at: datetime
}

SeasonRules {
  payouts: decimal[],       // e.g. [18, 12, 10, 8, 7, 6, 5, 4, 3, 2]
  tie_floor: decimal,       // minimum payout per tied position (default: 1)
  side_bet_rounds: int[],   // which draft rounds have side bets (default: [5,6,7,8])
  side_bet_amount: decimal  // per-team side bet amount (default: 15)
}

Golfer {
  id: UUID, pga_player_id: string?, first_name: string, last_name: string,
  country: string?, world_ranking: int?, active: boolean, updated_at: datetime
}

Tournament {
  id: UUID, pga_tournament_id: string?, name: string, season_id: UUID,
  start_date: date, end_date: date, course_name: string?,
  status: string, purse_amount: long?, payout_multiplier: decimal,
  week: string?, created_at: datetime
}

TournamentResult {
  id: UUID, tournament_id: UUID, golfer_id: UUID,
  position: int?, score_to_par: int?, total_strokes: int?,
  earnings: long?, round1: int?, round2: int?, round3: int?, round4: int?,
  made_cut: boolean
}

Team {
  id: UUID, season_id: UUID, owner_name: string, team_name: string,
  team_number: int?, created_at: datetime, updated_at: datetime
}

RosterEntry {
  id: UUID, team_id: UUID, golfer_id: UUID, acquired_via: string,
  draft_round: int?, ownership_pct: decimal,
  acquired_at: datetime, dropped_at: datetime?, is_active: boolean
}

Draft {
  id: UUID, season_id: UUID, status: string, draft_type: string,
  started_at: datetime?, completed_at: datetime?, created_at: datetime
}

DraftPick {
  id: UUID, draft_id: UUID, team_id: UUID, golfer_id: UUID?,
  round_num: int, pick_num: int, picked_at: datetime?
}

FantasyScore {
  id: UUID, season_id: UUID, team_id: UUID, tournament_id: UUID,
  golfer_id: UUID, points: decimal, position: int, num_tied: int,
  base_payout: decimal, ownership_pct: decimal, payout: decimal,
  multiplier: decimal, calculated_at: datetime
}

SeasonStanding {
  id: UUID, season_id: UUID, team_id: UUID,
  total_points: decimal, tournaments_played: int, last_updated: datetime
}
```

### Create/Update DTOs

```
CreateLeague             { name }
CreateSeason             { league_id, name, season_year, season_number?, max_teams?, tie_floor?, side_bet_amount? }
UpdateSeason             { name?, status?, max_teams?, tie_floor?, side_bet_amount? }
CreateGolfer             { pga_player_id?, first_name, last_name, country?, world_ranking? }
UpdateGolfer             { pga_player_id?, first_name?, last_name?, country?, world_ranking?, active? }
CreateTournament         { pga_tournament_id?, name, season_id, start_date, end_date, course_name?, purse_amount?, payout_multiplier?, week? }
UpdateTournament         { name?, start_date?, end_date?, course_name?, status?, purse_amount?, payout_multiplier? }
CreateTournamentResult   { golfer_id, position?, score_to_par?, total_strokes?, earnings?, round1?, round2?, round3?, round4?, made_cut }
CreateTeam               { owner_name, team_name, team_number? }
UpdateTeam               { owner_name?, team_name? }
AddToRoster              { golfer_id, acquired_via?, draft_round?, ownership_pct? }
CreateDraft              { draft_type? }
MakePick                 { team_id, golfer_id }
```

### Report Models

```
WeeklyReport {
  tournament: ReportTournamentInfo,
  teams: ReportTeamColumn[],
  undrafted_top_tens: UndraftedGolfer[],
  side_bet_detail: ReportSideBetRound[],
  standings_order: StandingsEntry[],
  live: boolean?
}

ReportTournamentInfo {
  id: UUID?, name: string?, start_date: string?, end_date: string?,
  status: string?, payout_multiplier: decimal, week: string?
}

ReportTeamColumn {
  team_id: UUID, team_name: string, owner_name: string,
  rows: ReportRow[],           // 8 rows, one per draft round
  top_tens: decimal,           // sum of this week's top-10 earnings
  weekly_total: decimal,       // zero-sum weekly amount
  previous: decimal,           // cumulative prior weeks
  subtotal: decimal,           // previous + weekly_total
  top_ten_count: int,          // number of top-10 finishes
  top_ten_money: decimal,      // total top-10 earnings
  side_bets: decimal,          // net side bet P&L
  total_cash: decimal          // subtotal + side_bets
}

ReportRow {
  round: int, golfer_name: string?, golfer_id: UUID?,
  position_str: string?, score_to_par: string?,
  earnings: decimal, top_tens: int, ownership_pct: decimal,
  season_earnings: decimal, season_top_tens: int
}

UndraftedGolfer {
  name: string, position: int?, payout: decimal, score_to_par: string?
}

ReportSideBetRound {
  round: int,
  teams: ReportSideBetTeamEntry[]
}

ReportSideBetTeamEntry {
  team_id: UUID, golfer_name: string,
  cumulative_earnings: decimal, payout: decimal
}

StandingsEntry {
  rank: int, team_name: string, total_cash: decimal
}

Rankings {
  teams: TeamRanking[],
  weeks: string[],
  tournament_names: string[],
  live: boolean?
}

TeamRanking {
  team_id: UUID, team_name: string,
  subtotal: decimal, side_bets: decimal, total_cash: decimal,
  series: decimal[],           // cumulative total at each tournament
  live_weekly: decimal?        // live projected weekly total
}

GolferHistory {
  golfer_name: string, golfer_id: UUID,
  total_earnings: decimal, top_tens: int,
  results: GolferHistoryEntry[]
}

GolferHistoryEntry {
  tournament: string, position: int, earnings: decimal
}
```

### Scoring Models

```
WeeklyScoreResult {
  tournament_id: UUID, multiplier: decimal,
  num_teams: int, total_pot: decimal,
  teams: TeamWeeklyResult[]
}

TeamWeeklyResult {
  team_id: UUID, team_name: string,
  top_tens: decimal, weekly_total: decimal,
  golfer_scores: GolferScoreEntry[]
}

GolferScoreEntry {
  golfer_id: UUID, payout: decimal, breakdown: ScoreBreakdown
}

ScoreBreakdown {
  position: int, num_tied: int, base_payout: decimal,
  ownership_pct: decimal, payout: decimal, multiplier: decimal
}

SideBetStandings {
  rounds: SideBetRound[], team_totals: SideBetTeamTotal[]
}

SideBetRound {
  round: int, active: boolean,
  winner: SideBetWinner?, entries: SideBetEntry[]
}

SideBetEntry {
  team_id: UUID, team_name: string,
  golfer_id: UUID, cumulative_earnings: decimal
}

SideBetWinner extends SideBetEntry {
  net_winnings: decimal
}

SideBetTeamTotal {
  team_id: UUID, team_name: string, wins: int, net: decimal
}
```

### ESPN Import Models

```
EspnImportResult {
  tournament_id: UUID, espn_name: string, espn_id: string,
  completed: boolean, total_competitors: int,
  matched: int, unmatched: string[], created: int,
  collisions: string[]
}

EspnLivePreview {
  espn_name: string, espn_id: string, completed: boolean,
  payout_multiplier: decimal, total_competitors: int,
  teams: PreviewTeamScore[], leaderboard: PreviewLeaderboardEntry[]
}

PreviewTeamScore {
  team_id: UUID, team_name: string, owner_name: string,
  top_ten_earnings: decimal, golfer_scores: PreviewGolferScore[],
  weekly_total: decimal
}

PreviewGolferScore {
  golfer_name: string, golfer_id: UUID,
  position: int, num_tied: int, score_to_par: int?,
  base_payout: decimal, ownership_pct: decimal, payout: decimal
}

PreviewLeaderboardEntry {
  name: string, position: int, score_to_par: int?,
  thru: string?, rostered: boolean, team_name: string?
}
```

### Admin Models

```
SeasonUploadResult {
  season_year: int, tournaments_created: int,
  tournaments: TournamentCreated[],
  espn_matched: int, espn_unmatched: string[]
}

TournamentCreated {
  id: UUID, name: string, week: string,
  start_date: date, end_date: date, payout_multiplier: decimal,
  espn_id: string?, espn_name: string?
}

RosterPreviewResult {
  teams: RosterTeamPreview[], total_picks: int,
  exact_matches: int, ambiguous: int, no_match: int
}

RosterTeamPreview {
  team_number: int, team_name: string, picks: RosterPickPreview[]
}

RosterPickPreview {
  round: int, input_name: string, ownership_pct: int,
  match_status: string, espn_id: string?, espn_name: string?,
  suggestions: EspnSuggestion[]
}

EspnSuggestion { espn_id: string, name: string }

ConfirmedTeam {
  team_number: int, team_name: string, picks: ConfirmedPick[]
}

ConfirmedPick {
  round: int, player_name: string, ownership_pct: int,
  espn_id: string?, espn_name: string?
}

RosterUploadResult {
  teams_created: int, golfers_created: int, teams: TeamUploadResult[]
}

TeamUploadResult {
  team_id: UUID, team_number: int, team_name: string,
  picks: RosterPickResult[]
}

RosterPickResult {
  round: int, golfer_name: string, golfer_id: UUID,
  ownership_pct: int, created: boolean
}

CalendarEntryResponse { espn_id: string, name: string, start_date: string }
```

### Roster View Models

```
RosterViewTeam {
  team_id: UUID, team_name: string, picks: RosterViewPick[]
}

RosterViewPick {
  round: int, golfer_name: string, ownership_pct: decimal, golfer_id: UUID
}
```

---

## API Endpoints

All endpoints return JSON with snake_case keys. Error responses use `{ "error": "message" }`.

### Authentication

Session-based via `cwfgw_session` cookie. HttpOnly, SameSite=Strict, path=/.

| Method | Path | Auth | Description |
|---|---|---|---|
| POST | `/api/v1/auth/login` | No | Login, sets session cookie |
| POST | `/api/v1/auth/logout` | No | Clears session cookie |
| GET | `/api/v1/auth/me` | No | Returns auth status |

**POST /api/v1/auth/login**
- Body: `{ "username": string, "password": string }`
- 200: `{ "ok": true }` + `Set-Cookie: cwfgw_session=<token>`
- 403: `{ "error": "Invalid credentials" }`

**POST /api/v1/auth/logout**
- 200: `{ "ok": true }` + clears cookie

**GET /api/v1/auth/me**
- 200: `{ "authenticated": bool, "username": string? }`

### Health

| Method | Path | Auth | Description |
|---|---|---|---|
| GET | `/api/v1/health` | No | Health check with DB ping |

- 200: `{ "status": "ok", "service": "cwfgw", "database": "connected" }`
- 500: `{ "status": "degraded", "service": "cwfgw", "database": "unreachable" }`

### Leagues

| Method | Path | Auth | Description |
|---|---|---|---|
| GET | `/api/v1/leagues` | No | List all leagues |
| GET | `/api/v1/leagues/{id}` | No | Get league by ID |
| POST | `/api/v1/leagues` | No | Create league |

### Seasons

| Method | Path | Auth | Description |
|---|---|---|---|
| GET | `/api/v1/seasons?league_id=&year=` | No | List seasons (filterable) |
| GET | `/api/v1/seasons/{id}` | No | Get season |
| POST | `/api/v1/seasons` | No | Create season |
| PUT | `/api/v1/seasons/{id}` | No | Update season |
| GET | `/api/v1/seasons/{id}/standings` | No | Get standings |

### Golfers

| Method | Path | Auth | Description |
|---|---|---|---|
| GET | `/api/v1/golfers?active=&search=` | No | List golfers (default active=true) |
| GET | `/api/v1/golfers/{id}` | No | Get golfer |
| POST | `/api/v1/golfers` | No | Create golfer |
| PUT | `/api/v1/golfers/{id}` | No | Update golfer |

### Teams & Rosters

| Method | Path | Auth | Description |
|---|---|---|---|
| GET | `/api/v1/seasons/{sid}/teams` | No | List teams in season |
| GET | `/api/v1/seasons/{sid}/teams/{tid}` | No | Get team |
| POST | `/api/v1/seasons/{sid}/teams` | No | Create team |
| PUT | `/api/v1/seasons/{sid}/teams/{tid}` | No | Update team |
| GET | `/api/v1/seasons/{sid}/teams/{tid}/roster` | No | Get team roster |
| POST | `/api/v1/seasons/{sid}/teams/{tid}/roster` | No | Add to roster |
| DELETE | `/api/v1/seasons/{sid}/teams/{tid}/roster/{golferId}` | No | Drop from roster |
| GET | `/api/v1/seasons/{sid}/rosters` | No | All rosters (display view) |

### Draft

| Method | Path | Auth | Description |
|---|---|---|---|
| GET | `/api/v1/seasons/{sid}/draft` | No | Get draft |
| POST | `/api/v1/seasons/{sid}/draft` | No | Create draft |
| POST | `/api/v1/seasons/{sid}/draft/start` | No | Start draft |
| POST | `/api/v1/seasons/{sid}/draft/initialize?rounds=` | No | Generate pick order (default 6 rounds) |
| POST | `/api/v1/seasons/{sid}/draft/pick` | No | Make a pick |
| GET | `/api/v1/seasons/{sid}/draft/picks` | No | List all picks |
| GET | `/api/v1/seasons/{sid}/draft/available` | No | Available golfers |

**POST /api/v1/seasons/{sid}/draft/pick**
- Body: `{ "team_id": UUID, "golfer_id": UUID }`

### Tournaments

| Method | Path | Auth | Description |
|---|---|---|---|
| GET | `/api/v1/tournaments?season_id=&status=` | No | List tournaments |
| GET | `/api/v1/tournaments/{id}` | No | Get tournament |
| POST | `/api/v1/tournaments` | No | Create tournament |
| PUT | `/api/v1/tournaments/{id}` | No | Update tournament |
| GET | `/api/v1/tournaments/{id}/results` | No | Get results |
| POST | `/api/v1/tournaments/{id}/results` | No | Import results (batch) |

### Scoring

| Method | Path | Auth | Description |
|---|---|---|---|
| GET | `/api/v1/seasons/{sid}/scoring/{tid}` | No | Get scores for tournament |
| POST | `/api/v1/seasons/{sid}/scoring/calculate/{tid}` | No | Calculate scores |
| POST | `/api/v1/seasons/{sid}/scoring/refresh-standings` | No | Refresh standings |
| GET | `/api/v1/seasons/{sid}/scoring/side-bets` | No | Side bet standings |

### Reports

| Method | Path | Auth | Description |
|---|---|---|---|
| GET | `/api/v1/seasons/{sid}/report/{tid}?live=` | No | Tournament report |
| GET | `/api/v1/seasons/{sid}/report?live=` | No | Season report |
| GET | `/api/v1/seasons/{sid}/rankings?live=&through=` | No | Rankings (cumulative chart data) |
| GET | `/api/v1/seasons/{sid}/golfer/{gid}/history` | No | Golfer history |

### ESPN (Public)

| Method | Path | Auth | Description |
|---|---|---|---|
| GET | `/api/v1/espn/preview/{sid}?date=` | No | Live preview for a date |
| GET | `/api/v1/espn/calendar` | No | PGA schedule |

### Admin (Authenticated)

All admin routes require a valid `cwfgw_session` cookie.

| Method | Path | Auth | Description |
|---|---|---|---|
| POST | `/api/v1/admin/season` | Yes | Upload season schedule |
| POST | `/api/v1/admin/roster/preview` | Yes | Preview roster upload |
| POST | `/api/v1/admin/roster/confirm` | Yes | Confirm roster upload |
| GET | `/api/v1/admin/espn-calendar` | Yes | ESPN calendar (admin view) |
| POST | `/api/v1/tournaments/{id}/finalize` | Yes | Finalize tournament |
| POST | `/api/v1/tournaments/{id}/reset` | Yes | Reset tournament |
| POST | `/api/v1/seasons/{sid}/finalize` | Yes | Finalize season |
| POST | `/api/v1/seasons/{sid}/clean-results` | Yes | Clean all season results |
| POST | `/api/v1/espn/import?date=` | Yes | Import ESPN results by date |
| POST | `/api/v1/espn/import/tournament/{tid}` | Yes | Import ESPN for specific tournament |

**POST /api/v1/admin/season**
- Body: `{ "season_id": UUID, "season_year": int, "schedule": string }`
- The `schedule` field is a plain-text tournament schedule in a custom format

**POST /api/v1/admin/roster/preview**
- Body: `{ "roster": string }`
- Returns match status and suggestions for each player

**POST /api/v1/admin/roster/confirm**
- Body: `{ "season_id": UUID, "teams": ConfirmedTeam[] }`

### Static Files

| Method | Path | Description |
|---|---|---|
| GET | `/` | Serves `index.html` |
| GET | `/static/{file}` | Serves static assets (js, css, html, svg, png) |

---

## Business Logic

### Scoring Calculation

When a tournament is finalized, scores are calculated for each team:

1. **For each team**, iterate over active roster entries
2. **For each rostered golfer**, check if they have a tournament result with `position ≤ payout_zone_size` (default: 10)
3. **Tie-split payout**:
   - If not tied: `payout = payouts[position - 1] * multiplier`
   - If tied: average the payout amounts for all tied positions, floor to `tie_floor`, then multiply
   - Example: T4 with 3 tied, payouts `[$8, $7, $6]` for positions 4-6 → `avg = $7.00` → `× multiplier`
   - If the tie spans beyond the payout zone, only in-zone positions contribute to the average
4. **Ownership split**: if multiple teams own the same golfer (shared ownership), split the base payout proportionally
   - Sort owners by ownership% descending
   - Round each share to 4 decimal places (HALF_UP), last owner gets remainder
5. **Persist** a `FantasyScore` for each team/golfer/tournament combination

### Zero-Sum Weekly Totals

Each week's earnings redistribute a fixed pool across teams:

```
team_top_tens    = sum of all payout amounts for this team's golfers this week
total_pot        = sum of all teams' top_tens
team_weekly      = (team_top_tens × num_teams) - total_pot
```

This guarantees `sum(team_weekly for all teams) = 0`.

### Side Bets

Side bets are independent competitions per draft round:

1. **Configured rounds**: e.g., rounds 5, 6, 7, 8 (the later draft picks)
2. **For each round**: look at every team's golfer drafted in that round
3. **Cumulative earnings**: sum each team's golfer earnings (from `fantasy_scores`) across all tournaments through the current one, weighted by `ownership_pct`
4. **Winner determination**: team with highest cumulative earnings wins
5. **Payouts**:
   - Winner collects: `side_bet_amount × (num_teams - num_winners) / num_winners`
   - Each loser pays: `side_bet_amount`
   - Ties split the winner's pot equally
6. **Inactive round**: if no earnings exist or all are zero, the round has no payout
7. **Season total**: sum of net P&L across all rounds

### Report Assembly

The weekly report is a grid: **8 rows** (draft rounds) × **N columns** (teams).

For each team column:
1. One row per draft round, showing: golfer name, position, score-to-par, weekly earnings, ownership%, cumulative season earnings
2. Summary rows: `top_tens`, `weekly_total` (zero-sum), `previous` (sum of prior weeks' weekly totals), `subtotal`, `side_bets`, `total_cash`
3. **Undrafted top-tens**: golfers not on any roster who finished in the payout zone
4. **Side bet detail**: per-round breakdown showing each team's golfer and cumulative earnings
5. **Standings order**: teams sorted by `total_cash` descending with rank numbers

### Season Report

Aggregates all completed tournaments. Each row shows season-wide totals instead of single-tournament data.

### Rankings (Cumulative Chart Data)

Returns a time-series suitable for charting:
- For each team: `series[]` contains the cumulative `total_cash` at each tournament
- `weeks[]` and `tournament_names[]` provide x-axis labels
- Side bet P&L is recalculated at each point in the series

### Live Overlay

When `?live=true` is passed to report or rankings endpoints:

1. Identify non-completed tournaments (upcoming or in_progress) that should be overlaid
2. Fetch ESPN live leaderboard for each
3. Compute projected payouts using live positions (same tie-split logic)
4. Merge onto the base report:
   - Replace per-golfer earnings with projected amounts
   - Recompute zero-sum weekly totals
   - Update side bet cumulative earnings and winners
   - Add live weekly total to rankings series
5. Flag the response with `live: true`
6. **Never persist** any live data

### Tournament Finalization Pipeline

Guarded by a mutex (serialized, one at a time):

1. Validate tournament exists
2. Check no earlier tournaments in the same season are unfinalized (reject if so)
3. Import ESPN results (by ESPN ID if linked, else by date)
4. Calculate scores for all teams
5. Refresh season standings
6. Mark tournament as completed

### Tournament Reset

Also guarded by the same mutex:

1. Validate tournament exists
2. Check no later tournaments have been finalized (reject if so)
3. Delete all `fantasy_scores` for the tournament
4. Delete all `tournament_results`
5. Set tournament status back to `upcoming`
6. Refresh season standings

### Draft — Snake Order

```
for round 1..R:
  if round is odd:  teams pick in order [1, 2, ..., N]
  if round is even: teams pick in reverse [N, N-1, ..., 1]

pick_num increments globally: 1, 2, ..., N×R
```

### Golfer Matching (ESPN Import)

Priority order when matching ESPN competitors to database golfers:

1. **ESPN athlete ID** → `golfers.pga_player_id` (exact)
2. **Full name** → `(first_name, last_name)` case-insensitive
3. **Unique last name** → if only one golfer has that last name and ESPN first name is absent/abbreviated (≤2 chars)
4. **Auto-create** → insert new golfer with ESPN name and ID

Collision detection: warn if multiple ESPN competitors resolve to the same golfer ID.

### Roster Upload Matching (Admin)

Fuzzy matching of player names from a pasted roster:

1. Hard-coded aliases (e.g., "AN" → "Byeong-Hun An")
2. Exact last-name match (diacritics-normalized)
3. Fuzzy last-name (edit distance ≤ 1 for short names, ≤ 2 for longer)
4. First-name narrowing (handles initials, abbreviations)
5. Full-name fuzzy fallback (edit distance ≤ 3)

Diacritics normalization: NFD decomposition + special cases (ø→o, đ→d, ł→l).

---

## ESPN Integration

### API Endpoints Used

Base URL: `https://site.api.espn.com/apis/site/v2/sports/golf`

| Tour | URL |
|---|---|
| PGA Tour | `.../pga/scoreboard` |
| LIV Golf | `.../liv/scoreboard` |
| DP World Tour | `.../eur/scoreboard` |

### Scoreboard Query

`GET {base}?dates={YYYYMMDD}` — returns JSON with events, competitions, competitors.

### Key ESPN JSON Structure

```
{
  "events": [{
    "id": "401580123",
    "name": "The Masters",
    "status": { "type": { "completed": true } },
    "competitions": [{
      "competitors": [{
        "id": "1234",
        "order": 1,
        "score": "-12",
        "status": { "type": { "id": "1" } },
        "athlete": { "displayName": "Scottie Scheffler" },
        "linescores": [{ "value": 68.0 }, { "value": 65.0 }, ...]
      }]
    }]
  }],
  "leagues": [{
    "calendar": [{ "id": "401580123", "label": "The Masters", "startDate": "2026-04-09T..." }]
  }]
}
```

### Competitor Status Codes

| ESPN status.type.id | Meaning | Made Cut? |
|---|---|---|
| `1` | Active/completed | Yes (if ≥3 rounds) |
| `2` | Cut | No |
| `3` | Withdrawn | No |
| `4` | Disqualified | No |

### Position Assignment

ESPN's `order` field is sequential (1, 2, 3...) but doesn't reflect ties. Positions are reassigned by grouping consecutive competitors with the same `score` string — all tied players share the first position in the group.

### Score Parsing

- `"E"` → 0 (even par)
- `"+5"` → 5
- `"-3"` → -3

### Client Configuration

- 10-second connect timeout on the HTTP client
- SSL context configured with macOS KeychainStore (for corporate proxy compatibility), falls back to JDK defaults

---

## Authentication

### Session Management

- **Storage**: in-memory map of `token → username` (ephemeral; lost on restart)
- **Token**: UUID generated on login
- **Cookie**: `cwfgw_session`, HttpOnly, SameSite=Strict, path=/
- **Seeding**: on startup, if no users exist, creates a single admin user with BCrypt-hashed password
- **Credentials**: configured via `ADMIN_USERNAME` and `ADMIN_PASSWORD` environment variables (required, no defaults)

### Middleware Behavior

Protected routes are wrapped in auth middleware:
- Extracts `cwfgw_session` cookie from the request
- Validates token against the session map
- If valid: passes request through to protected routes
- If invalid or missing: skips the request (returns 404 from the public route fallthrough)

---

## Infrastructure

### Configuration

Required environment variables:

| Variable | Description |
|---|---|
| `ADMIN_USERNAME` | Admin login username |
| `ADMIN_PASSWORD` | Admin login password |
| `DATABASE_URL` | PostgreSQL JDBC URL (optional, default: `jdbc:postgresql://localhost:5432/cwfgw`) |
| `DATABASE_USER` | Database user (optional, default: `cwfgw`) |
| `DATABASE_PASSWORD` | Database password (optional, default: `cwfgw`) |
| `SERVER_HOST` | Bind host (optional, default: `0.0.0.0`) |
| `SERVER_PORT` | Bind port (optional, default: `8080`) |

### Startup Sequence

1. Load configuration
2. Run Flyway migrations (60s timeout, 3 connect retries)
3. Allocate in-memory session store
4. Create database connection pool
5. Initialize ESPN HTTP client
6. Wire all services
7. Seed admin user (if no users exist)
8. Start HTTP server

### Error Handling

- Global error-handling middleware catches unhandled exceptions → returns `500 { "error": "Internal server error" }` + logs the error
- Service-level error handling on finalize/reset/clean operations → catches and wraps exceptions as `Left(message)`
- Route-level error handling on ESPN, report, admin, and scoring routes → returns `400 { "error": "message" }`

### Health Check

`GET /api/v1/health` executes `SELECT 1` against the database:
- Success → `200 { "status": "ok", "database": "connected" }`
- Failure → `500 { "status": "degraded", "database": "unreachable" }`

### Database

- PostgreSQL with HikariCP connection pool (configurable pool size, default 10)
- Flyway migrations in `classpath:db/migration`
- All IDs are `gen_random_uuid()`
- Financial columns use `NUMERIC` with explicit scale (never floating point)
