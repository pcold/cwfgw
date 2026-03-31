CREATE TABLE tournaments (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pga_tournament_id TEXT UNIQUE,
    name              TEXT NOT NULL,
    season_year       INT NOT NULL,
    start_date        DATE NOT NULL,
    end_date          DATE NOT NULL,
    course_name       TEXT,
    status            TEXT NOT NULL DEFAULT 'upcoming',
    purse_amount      BIGINT,
    metadata          JSONB NOT NULL DEFAULT '{}',
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_tournaments_season ON tournaments(season_year);
CREATE INDEX idx_tournaments_dates ON tournaments(start_date, end_date);

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
