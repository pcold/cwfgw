CREATE TABLE teams (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    league_id  UUID NOT NULL REFERENCES leagues(id),
    owner_name TEXT NOT NULL,
    team_name  TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(league_id, team_name)
);

CREATE INDEX idx_teams_league ON teams(league_id);

CREATE TABLE team_rosters (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    team_id      UUID NOT NULL REFERENCES teams(id),
    golfer_id    UUID NOT NULL REFERENCES golfers(id),
    acquired_via TEXT NOT NULL DEFAULT 'draft',
    acquired_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    dropped_at   TIMESTAMPTZ,
    is_active    BOOLEAN NOT NULL DEFAULT true,
    UNIQUE(team_id, golfer_id, acquired_at)
);

CREATE INDEX idx_roster_team_active ON team_rosters(team_id) WHERE dropped_at IS NULL;
CREATE INDEX idx_roster_golfer_active ON team_rosters(golfer_id) WHERE dropped_at IS NULL;
