-- V010: Seed 2026 Castlewood Fantasy Golf league
-- Only the league is seeded; tournaments and teams are uploaded via admin UI.

-- League
INSERT INTO leagues (id, name, season_year, status, max_teams, rules)
VALUES (
  '11111111-1111-1111-1111-111111111111',
  'Castlewood Fantasy Golf 2026',
  2026,
  'active',
  13,
  '{"payout_structure": [18,12,10,8,7,6,5,4,3,2], "side_bet_rounds": [5,6,7,8], "side_bet_per_team": 15}'
);
