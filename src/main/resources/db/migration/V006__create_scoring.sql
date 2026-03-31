CREATE TABLE fantasy_scores (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    league_id     UUID NOT NULL REFERENCES leagues(id),
    team_id       UUID NOT NULL REFERENCES teams(id),
    tournament_id UUID NOT NULL REFERENCES tournaments(id),
    golfer_id     UUID NOT NULL REFERENCES golfers(id),
    points        NUMERIC(10,2) NOT NULL DEFAULT 0,
    breakdown     JSONB NOT NULL DEFAULT '{}',
    calculated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(league_id, team_id, tournament_id, golfer_id)
);

CREATE INDEX idx_scores_league_tournament ON fantasy_scores(league_id, tournament_id);
CREATE INDEX idx_scores_team ON fantasy_scores(team_id);

CREATE TABLE league_standings (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    league_id          UUID NOT NULL REFERENCES leagues(id),
    team_id            UUID NOT NULL REFERENCES teams(id),
    total_points       NUMERIC(12,2) NOT NULL DEFAULT 0,
    tournaments_played INT NOT NULL DEFAULT 0,
    last_updated       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(league_id, team_id)
);
