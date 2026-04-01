-- V001: Full schema for Castlewood Fantasy Golf
-- League → Season → Tournament hierarchy

-- Leagues (top-level)
CREATE TABLE leagues (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name       TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO leagues (id, name)
VALUES ('00000000-0000-0000-0000-000000000001', 'Castlewood Fantasy Golf');

-- Seasons
CREATE TABLE seasons (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    league_id     UUID NOT NULL REFERENCES leagues(id),
    name          TEXT NOT NULL,
    season_year   INT NOT NULL,
    season_number INT NOT NULL DEFAULT 1,
    status        TEXT NOT NULL DEFAULT 'draft',
    rules         JSONB NOT NULL DEFAULT '{}',
    max_teams     INT NOT NULL DEFAULT 10,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_seasons_year ON seasons(season_year);

-- Golfers
CREATE TABLE golfers (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pga_player_id TEXT UNIQUE,
    first_name    TEXT NOT NULL,
    last_name     TEXT NOT NULL,
    country       TEXT,
    world_ranking INT,
    active        BOOLEAN NOT NULL DEFAULT true,
    metadata      JSONB NOT NULL DEFAULT '{}',
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_golfers_ranking ON golfers(world_ranking);
CREATE INDEX idx_golfers_name ON golfers(last_name, first_name);

-- Tournaments
CREATE TABLE tournaments (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pga_tournament_id TEXT UNIQUE,
    name              TEXT NOT NULL,
    season_id         UUID REFERENCES seasons(id),
    start_date        DATE NOT NULL,
    end_date          DATE NOT NULL,
    course_name       TEXT,
    status            TEXT NOT NULL DEFAULT 'upcoming',
    purse_amount      BIGINT,
    payout_multiplier NUMERIC(4,2) NOT NULL DEFAULT 1.00,
    metadata          JSONB NOT NULL DEFAULT '{}',
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_tournaments_season ON tournaments(season_id);
CREATE INDEX idx_tournaments_dates ON tournaments(start_date, end_date);

-- Tournament Results
CREATE TABLE tournament_results (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tournament_id UUID NOT NULL REFERENCES tournaments(id),
    golfer_id     UUID NOT NULL REFERENCES golfers(id),
    position      INT,
    score_to_par  INT,
    total_strokes INT,
    earnings      BIGINT,
    round_scores  JSONB,
    made_cut      BOOLEAN NOT NULL DEFAULT true,
    metadata      JSONB NOT NULL DEFAULT '{}',
    UNIQUE(tournament_id, golfer_id)
);

CREATE INDEX idx_results_tournament ON tournament_results(tournament_id);
CREATE INDEX idx_results_golfer ON tournament_results(golfer_id);

-- Teams
CREATE TABLE teams (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    season_id   UUID NOT NULL REFERENCES seasons(id),
    owner_name  TEXT NOT NULL,
    team_name   TEXT NOT NULL,
    team_number INT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(season_id, team_name)
);

CREATE INDEX idx_teams_season ON teams(season_id);

-- Team Rosters
CREATE TABLE team_rosters (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    team_id       UUID NOT NULL REFERENCES teams(id),
    golfer_id     UUID NOT NULL REFERENCES golfers(id),
    acquired_via  TEXT NOT NULL DEFAULT 'draft',
    draft_round   INT,
    ownership_pct NUMERIC(5,2) NOT NULL DEFAULT 100.00,
    acquired_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    dropped_at    TIMESTAMPTZ,
    is_active     BOOLEAN NOT NULL DEFAULT true,
    UNIQUE(team_id, golfer_id, acquired_at)
);

CREATE INDEX idx_roster_team_active ON team_rosters(team_id) WHERE dropped_at IS NULL;
CREATE INDEX idx_roster_golfer_active ON team_rosters(golfer_id) WHERE dropped_at IS NULL;

-- Drafts
CREATE TABLE drafts (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    season_id    UUID NOT NULL REFERENCES seasons(id) UNIQUE,
    status       TEXT NOT NULL DEFAULT 'pending',
    draft_type   TEXT NOT NULL DEFAULT 'snake',
    settings     JSONB NOT NULL DEFAULT '{}',
    started_at   TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Draft Picks
CREATE TABLE draft_picks (
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    draft_id  UUID NOT NULL REFERENCES drafts(id),
    team_id   UUID NOT NULL REFERENCES teams(id),
    golfer_id UUID REFERENCES golfers(id),
    round_num INT NOT NULL,
    pick_num  INT NOT NULL,
    picked_at TIMESTAMPTZ,
    UNIQUE(draft_id, pick_num)
);

CREATE INDEX idx_picks_draft ON draft_picks(draft_id);
CREATE INDEX idx_picks_team ON draft_picks(team_id);

-- Fantasy Scores
CREATE TABLE fantasy_scores (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    season_id     UUID NOT NULL REFERENCES seasons(id),
    team_id       UUID NOT NULL REFERENCES teams(id),
    tournament_id UUID NOT NULL REFERENCES tournaments(id),
    golfer_id     UUID NOT NULL REFERENCES golfers(id),
    points        NUMERIC(10,2) NOT NULL DEFAULT 0,
    breakdown     JSONB NOT NULL DEFAULT '{}',
    calculated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(season_id, team_id, tournament_id, golfer_id)
);

CREATE INDEX idx_scores_season_tournament ON fantasy_scores(season_id, tournament_id);
CREATE INDEX idx_scores_team ON fantasy_scores(team_id);

-- Season Standings
CREATE TABLE season_standings (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    season_id          UUID NOT NULL REFERENCES seasons(id),
    team_id            UUID NOT NULL REFERENCES teams(id),
    total_points       NUMERIC(12,2) NOT NULL DEFAULT 0,
    tournaments_played INT NOT NULL DEFAULT 0,
    last_updated       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(season_id, team_id)
);

-- Users (authentication)
CREATE TABLE users (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username      VARCHAR(50) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(20) NOT NULL DEFAULT 'admin',
    created_at    TIMESTAMP NOT NULL DEFAULT now()
);
