ALTER TABLE tournaments
    ALTER COLUMN payout_multiplier TYPE NUMERIC(6,4);

ALTER TABLE team_rosters
    ALTER COLUMN ownership_pct TYPE NUMERIC(7,4);

ALTER TABLE fantasy_scores
    ALTER COLUMN points TYPE NUMERIC(12,4);

ALTER TABLE season_standings
    ALTER COLUMN total_points TYPE NUMERIC(14,4);
