-- Rename leagues → seasons (FK references auto-follow)
ALTER TABLE leagues RENAME TO seasons;

-- Create top-level leagues table
CREATE TABLE leagues (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name       TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Seed a default league and backfill existing seasons
INSERT INTO leagues (id, name)
VALUES ('00000000-0000-0000-0000-000000000001', 'Castlewood Fantasy Golf');

-- Add league_id and season_number to seasons
ALTER TABLE seasons ADD COLUMN league_id UUID REFERENCES leagues(id);
ALTER TABLE seasons ADD COLUMN season_number INT NOT NULL DEFAULT 1;

-- Backfill league_id for any existing seasons
UPDATE seasons SET league_id = '00000000-0000-0000-0000-000000000001'
WHERE league_id IS NULL;

-- Now make league_id NOT NULL
ALTER TABLE seasons ALTER COLUMN league_id SET NOT NULL;

-- Rename league_id → season_id in child tables
ALTER TABLE teams RENAME COLUMN league_id TO season_id;
ALTER TABLE fantasy_scores RENAME COLUMN league_id TO season_id;
ALTER TABLE drafts RENAME COLUMN league_id TO season_id;

-- Rename league_standings → season_standings
ALTER TABLE league_standings RENAME TO season_standings;
ALTER TABLE season_standings RENAME COLUMN league_id TO season_id;

-- Add season_id FK to tournaments, drop season_year
ALTER TABLE tournaments ADD COLUMN season_id UUID REFERENCES seasons(id);
ALTER TABLE tournaments DROP COLUMN season_year;
