-- V010: Seed 2026 Castlewood Fantasy Golf season
-- Tournament schedule from the 1st Draft list (14 events, 13 weeks)
-- ESPN event IDs from site.api.espn.com

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

-- Tournaments (14 events across 13 weeks, 2 are Double $$)
INSERT INTO tournaments (id, pga_tournament_id, name, season_year, start_date, end_date, status, is_major, metadata) VALUES
  ('bbbbbbbb-bbbb-bbbb-bbbb-000000000001', '401811928', 'Sony Open', 2026, '2026-01-15', '2026-01-18', 'completed', false, '{"week": 1, "event": 1}'),
  ('bbbbbbbb-bbbb-bbbb-bbbb-000000000002', '401811929', 'The American Express', 2026, '2026-01-22', '2026-01-25', 'completed', false, '{"week": 2, "event": 2}'),
  ('bbbbbbbb-bbbb-bbbb-bbbb-000000000003', '401811930', 'Farmers Insurance Open', 2026, '2026-01-29', '2026-02-01', 'completed', false, '{"week": 3, "event": 3}'),
  ('bbbbbbbb-bbbb-bbbb-bbbb-000000000004', '401811931', 'WM Phoenix Open', 2026, '2026-02-05', '2026-02-08', 'completed', false, '{"week": 4, "event": 4}'),
  ('bbbbbbbb-bbbb-bbbb-bbbb-000000000005', '401811932', 'AT&T Pebble Beach', 2026, '2026-02-12', '2026-02-15', 'completed', false, '{"week": 5, "event": 5, "signature": true}'),
  ('bbbbbbbb-bbbb-bbbb-bbbb-000000000006', '401811933', 'Genesis Invitational', 2026, '2026-02-19', '2026-02-22', 'completed', false, '{"week": 6, "event": 6, "signature": true}'),
  ('bbbbbbbb-bbbb-bbbb-bbbb-000000000007', '401811934', 'Cognizant Classic', 2026, '2026-02-26', '2026-03-01', 'completed', false, '{"week": 7, "event": 7}'),
  ('bbbbbbbb-bbbb-bbbb-bbbb-000000000008', '401811935', 'Arnold Palmer Invitational', 2026, '2026-03-05', '2026-03-08', 'completed', false, '{"week": "8a", "event": 8, "signature": true}'),
  ('bbbbbbbb-bbbb-bbbb-bbbb-000000000009', '401811936', 'Puerto Rico Open', 2026, '2026-03-05', '2026-03-08', 'completed', false, '{"week": "8b", "event": 9}'),
  ('bbbbbbbb-bbbb-bbbb-bbbb-000000000010', '401811937', 'The Players Championship', 2026, '2026-03-12', '2026-03-15', 'completed', true, '{"week": 9, "event": 10, "double": true}'),
  ('bbbbbbbb-bbbb-bbbb-bbbb-000000000011', '401811938', 'Valspar Championship', 2026, '2026-03-19', '2026-03-22', 'completed', false, '{"week": 10, "event": 11}'),
  ('bbbbbbbb-bbbb-bbbb-bbbb-000000000012', '401811939', 'Texas Childrens Houston Open', 2026, '2026-03-26', '2026-03-29', 'upcoming', false, '{"week": 11, "event": 12}'),
  ('bbbbbbbb-bbbb-bbbb-bbbb-000000000013', '401811940', 'Valero Texas Open', 2026, '2026-04-02', '2026-04-05', 'upcoming', false, '{"week": 12, "event": 13}'),
  ('bbbbbbbb-bbbb-bbbb-bbbb-000000000014', '401811941', 'The Masters', 2026, '2026-04-09', '2026-04-12', 'upcoming', true, '{"week": 13, "event": 14, "double": true}');

-- Teams (13 teams in draft order)
INSERT INTO teams (id, league_id, owner_name, team_name) VALUES
  ('aaaaaaaa-aaaa-aaaa-aaaa-000000000001', '11111111-1111-1111-1111-111111111111', 'Brown', 'BROWN'),
  ('aaaaaaaa-aaaa-aaaa-aaaa-000000000002', '11111111-1111-1111-1111-111111111111', 'Burrell', 'BURRELL'),
  ('aaaaaaaa-aaaa-aaaa-aaaa-000000000003', '11111111-1111-1111-1111-111111111111', 'Poczik', 'POCZIK'),
  ('aaaaaaaa-aaaa-aaaa-aaaa-000000000004', '11111111-1111-1111-1111-111111111111', 'Kanham', 'KANHAM'),
  ('aaaaaaaa-aaaa-aaaa-aaaa-000000000005', '11111111-1111-1111-1111-111111111111', 'Roseh2o', 'ROSEH2O'),
  ('aaaaaaaa-aaaa-aaaa-aaaa-000000000006', '11111111-1111-1111-1111-111111111111', 'Kirk', 'KIRK'),
  ('aaaaaaaa-aaaa-aaaa-aaaa-000000000007', '11111111-1111-1111-1111-111111111111', 'Womble', 'WOMBLE'),
  ('aaaaaaaa-aaaa-aaaa-aaaa-000000000008', '11111111-1111-1111-1111-111111111111', 'Bhcp', 'BHCP'),
  ('aaaaaaaa-aaaa-aaaa-aaaa-000000000009', '11111111-1111-1111-1111-111111111111', 'Blau', 'BLAU'),
  ('aaaaaaaa-aaaa-aaaa-aaaa-000000000010', '11111111-1111-1111-1111-111111111111', 'Rowly', 'ROWLY'),
  ('aaaaaaaa-aaaa-aaaa-aaaa-000000000011', '11111111-1111-1111-1111-111111111111', 'Payray', 'PAYRAY'),
  ('aaaaaaaa-aaaa-aaaa-aaaa-000000000012', '11111111-1111-1111-1111-111111111111', 'Galbraith', 'GALBRAITH'),
  ('aaaaaaaa-aaaa-aaaa-aaaa-000000000013', '11111111-1111-1111-1111-111111111111', 'Obaz', 'OBAZ');
