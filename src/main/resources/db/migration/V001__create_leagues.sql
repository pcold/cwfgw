CREATE TABLE leagues (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        TEXT NOT NULL,
    season_year INT NOT NULL,
    status      TEXT NOT NULL DEFAULT 'draft',
    rules       JSONB NOT NULL DEFAULT '{}',
    max_teams   INT NOT NULL DEFAULT 10,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_leagues_season ON leagues(season_year);
