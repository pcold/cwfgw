-- V011: Seed 2026 team rosters (draft picks)
-- Maps golfers to teams based on the 8-round snake draft
-- Golfer names matched to ESPN-imported golfer records

-- Helper: insert roster entry by matching golfer name
-- Team BROWN (draft order 1)
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000001', id, 'draft', 1, 75 FROM golfers WHERE first_name = 'Scottie' AND last_name = 'Scheffler';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000001', id, 'draft', 2, 100 FROM golfers WHERE first_name = 'Justin' AND last_name = 'Rose';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000001', id, 'draft', 3, 100 FROM golfers WHERE first_name = 'Shane' AND last_name = 'Lowry';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000001', id, 'draft', 4, 100 FROM golfers WHERE first_name = 'Tony' AND last_name = 'Finau';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000001', id, 'draft', 5, 100 FROM golfers WHERE first_name = 'Rico' AND last_name = 'Gerard';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000001', id, 'draft', 6, 100 FROM golfers WHERE first_name = 'Patrick' AND last_name = 'Campbell';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000001', id, 'draft', 7, 100 FROM golfers WHERE first_name = 'John' AND last_name = 'Penge';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000001', id, 'draft', 8, 100 FROM golfers WHERE first_name = 'Jhonattan' AND last_name = 'Vegas';

-- Team BURRELL (draft order 2)
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000002', id, 'draft', 1, 100 FROM golfers WHERE first_name = 'Tommy' AND last_name = 'Fleetwood';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000002', id, 'draft', 2, 100 FROM golfers WHERE first_name = 'Chris' AND last_name = 'Gotterup';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000002', id, 'draft', 3, 100 FROM golfers WHERE first_name = 'Sungjae' AND last_name = 'Im';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000002', id, 'draft', 4, 100 FROM golfers WHERE first_name = 'Jason' AND last_name = 'Day';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000002', id, 'draft', 5, 100 FROM golfers WHERE first_name = 'Sami' AND last_name = 'Valimaki';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000002', id, 'draft', 6, 100 FROM golfers WHERE first_name = 'Daniel' AND last_name = 'Berger';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000002', id, 'draft', 7, 100 FROM golfers WHERE first_name = 'Eric' AND last_name = 'Cole';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000002', id, 'draft', 8, 100 FROM golfers WHERE first_name = 'Austin' AND last_name = 'Eckroat';

-- Team POCZIK (draft order 3)
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000003', id, 'draft', 1, 100 FROM golfers WHERE first_name = 'Xander' AND last_name = 'Schauffele';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000003', id, 'draft', 2, 100 FROM golfers WHERE first_name = 'Harris' AND last_name = 'English';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000003', id, 'draft', 3, 100 FROM golfers WHERE first_name = 'Alex' AND last_name = 'Noren';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000003', id, 'draft', 4, 100 FROM golfers WHERE first_name = 'Jake' AND last_name = 'Knapp';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000003', id, 'draft', 5, 100 FROM golfers WHERE first_name = 'Trevor' AND last_name = 'Keefer';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000003', id, 'draft', 6, 100 FROM golfers WHERE first_name = 'Nicolai' AND last_name = 'Hojgaard';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000003', id, 'draft', 7, 100 FROM golfers WHERE first_name = 'Billy' AND last_name = 'Horschel';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000003', id, 'draft', 8, 100 FROM golfers WHERE first_name = 'Tyson' AND last_name = 'Ford';

-- Team KANHAM (draft order 4)
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000004', id, 'draft', 1, 100 FROM golfers WHERE first_name = 'Rory' AND last_name = 'McIlroy';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000004', id, 'draft', 2, 100 FROM golfers WHERE first_name = 'Keegan' AND last_name = 'Bradley';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000004', id, 'draft', 3, 100 FROM golfers WHERE first_name = 'Aaron' AND last_name = 'Rai';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000004', id, 'draft', 4, 100 FROM golfers WHERE first_name ILIKE 'J.T%' AND last_name = 'Poston';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000004', id, 'draft', 5, 100 FROM golfers WHERE first_name = 'Christiaan' AND last_name = 'Bezuidenhout';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000004', id, 'draft', 6, 100 FROM golfers WHERE first_name = 'Lucas' AND last_name = 'Glover';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000004', id, 'draft', 7, 100 FROM golfers WHERE first_name = 'Will' AND last_name = 'Zalatoris';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000004', id, 'draft', 8, 100 FROM golfers WHERE first_name = 'Adam' AND last_name = 'Scott';

-- Team ROSEH2O (draft order 5)
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000005', id, 'draft', 1, 100 FROM golfers WHERE first_name = 'Ben' AND last_name = 'Griffin';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000005', id, 'draft', 2, 100 FROM golfers WHERE first_name = 'Corey' AND last_name = 'Conners';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000005', id, 'draft', 3, 100 FROM golfers WHERE first_name = 'Wyndham' AND last_name = 'Clark';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000005', id, 'draft', 4, 100 FROM golfers WHERE first_name = 'Brian' AND last_name = 'Harman';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000005', id, 'draft', 5, 100 FROM golfers WHERE first_name ILIKE 'Nic%' AND last_name = 'Echavarria';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000005', id, 'draft', 6, 100 FROM golfers WHERE first_name = 'Chris' AND last_name = 'Kirk';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000005', id, 'draft', 7, 100 FROM golfers WHERE first_name = 'Bryson' AND last_name = 'DeChambeau';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000005', id, 'draft', 8, 100 FROM golfers WHERE first_name = 'Patrick' AND last_name = 'Rodgers';

-- Team KIRK (draft order 6)
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000006', id, 'draft', 1, 100 FROM golfers WHERE first_name = 'Robert' AND last_name = 'MacIntyre';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000006', id, 'draft', 2, 100 FROM golfers WHERE first_name = 'Maverick' AND last_name = 'McNealy';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000006', id, 'draft', 3, 100 FROM golfers WHERE first_name = 'Nick' AND last_name = 'Taylor';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000006', id, 'draft', 4, 100 FROM golfers WHERE first_name ILIKE 'Jac%' AND last_name = 'Bridgeman';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000006', id, 'draft', 5, 100 FROM golfers WHERE first_name = 'Ryan' AND last_name = 'Fox';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000006', id, 'draft', 6, 100 FROM golfers WHERE first_name = 'Neal' AND last_name = 'Shipley';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000006', id, 'draft', 7, 100 FROM golfers WHERE first_name = 'Alex' AND last_name = 'Smalley';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000006', id, 'draft', 8, 100 FROM golfers WHERE first_name = 'Mackenzie' AND last_name = 'Hughes';

-- Team WOMBLE (draft order 7)
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000007', id, 'draft', 1, 25 FROM golfers WHERE first_name = 'Scottie' AND last_name = 'Scheffler';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000007', id, 'draft', 2, 100 FROM golfers WHERE first_name = 'Matt' AND last_name = 'Fitzpatrick';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000007', id, 'draft', 3, 100 FROM golfers WHERE first_name = 'Michael' AND last_name = 'Thorbjornsen';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000007', id, 'draft', 4, 100 FROM golfers WHERE first_name = 'Rasmus' AND last_name = 'Hojgaard';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000007', id, 'draft', 5, 100 FROM golfers WHERE first_name = 'Rickie' AND last_name = 'Fowler';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000007', id, 'draft', 6, 100 FROM golfers WHERE first_name ILIKE 'Vin%' AND last_name = 'Reitan';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000007', id, 'draft', 7, 100 FROM golfers WHERE first_name = 'Niklas' AND last_name = 'Norgaard Moelbak';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000007', id, 'draft', 8, 100 FROM golfers WHERE first_name = 'Thorbjorn' AND last_name = 'Olesen';

-- Team BHCP (draft order 8)
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000008', id, 'draft', 1, 100 FROM golfers WHERE first_name = 'Collin' AND last_name = 'Morikawa';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000008', id, 'draft', 2, 100 FROM golfers WHERE first_name = 'Hideki' AND last_name = 'Matsuyama';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000008', id, 'draft', 3, 100 FROM golfers WHERE first_name = 'Taylor' AND last_name = 'Pendrith';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000008', id, 'draft', 4, 100 FROM golfers WHERE first_name ILIKE 'Brend%' AND last_name = 'Brennan';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000008', id, 'draft', 5, 100 FROM golfers WHERE first_name = 'Aldrich' AND last_name = 'Potgieter';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000008', id, 'draft', 6, 100 FROM golfers WHERE first_name = 'Max' AND last_name = 'Homa';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000008', id, 'draft', 7, 100 FROM golfers WHERE first_name = 'Davis' AND last_name = 'Thompson';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000008', id, 'draft', 8, 100 FROM golfers WHERE first_name = 'Joe' AND last_name = 'Highsmith';

-- Team BLAU (draft order 9)
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000009', id, 'draft', 1, 100 FROM golfers WHERE first_name = 'Russell' AND last_name = 'Henley';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000009', id, 'draft', 2, 100 FROM golfers WHERE first_name = 'Viktor' AND last_name = 'Hovland';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000009', id, 'draft', 3, 100 FROM golfers WHERE first_name = 'Andrew' AND last_name = 'Novak';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000009', id, 'draft', 4, 100 FROM golfers WHERE first_name = 'Max' AND last_name = 'Greyserman';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000009', id, 'draft', 5, 100 FROM golfers WHERE first_name = 'Byeong Hun' AND last_name = 'An';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000009', id, 'draft', 6, 100 FROM golfers WHERE first_name = 'Thomas' AND last_name = 'Detry';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000009', id, 'draft', 7, 100 FROM golfers WHERE first_name = 'Nick' AND last_name = 'Dunlap';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000009', id, 'draft', 8, 100 FROM golfers WHERE first_name ILIKE 'Trace%' AND last_name ILIKE 'Suber%';

-- Team ROWLY (draft order 10)
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000010', id, 'draft', 1, 100 FROM golfers WHERE first_name = 'Justin' AND last_name = 'Thomas';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000010', id, 'draft', 2, 100 FROM golfers WHERE first_name ILIKE 'J.J%' AND last_name = 'Spaun';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000010', id, 'draft', 3, 100 FROM golfers WHERE first_name ILIKE 'Carson%' AND last_name ILIKE 'Clanton%';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000010', id, 'draft', 4, 100 FROM golfers WHERE first_name = 'Min Woo' AND last_name = 'Lee';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000010', id, 'draft', 5, 100 FROM golfers WHERE first_name = 'Jordan' AND last_name = 'Spieth';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000010', id, 'draft', 6, 100 FROM golfers WHERE first_name = 'Sahith' AND last_name = 'Theegala';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000010', id, 'draft', 7, 100 FROM golfers WHERE first_name = 'Tom' AND last_name = 'Kim';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000010', id, 'draft', 8, 100 FROM golfers WHERE first_name = 'Pierceson' AND last_name = 'Coody';

-- Team PAYRAY (draft order 11)
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000011', id, 'draft', 1, 100 FROM golfers WHERE first_name = 'Patrick' AND last_name = 'Cantlay';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000011', id, 'draft', 2, 100 FROM golfers WHERE first_name = 'Sam' AND last_name = 'Burns';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000011', id, 'draft', 3, 100 FROM golfers WHERE first_name = 'Akshay' AND last_name = 'Bhatia';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000011', id, 'draft', 4, 100 FROM golfers WHERE first_name = 'Si Woo' AND last_name = 'Kim';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000011', id, 'draft', 5, 100 FROM golfers WHERE first_name = 'Garrick' AND last_name = 'Higgo';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000011', id, 'draft', 6, 100 FROM golfers WHERE first_name = 'Stephan' AND last_name = 'Jaeger';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000011', id, 'draft', 7, 100 FROM golfers WHERE first_name = 'Emiliano' AND last_name = 'Grillo';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000011', id, 'draft', 8, 100 FROM golfers WHERE first_name = 'Austin' AND last_name = 'Smotherman';

-- Team GALBRAITH (draft order 12)
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000012', id, 'draft', 1, 100 FROM golfers WHERE first_name = 'Cameron' AND last_name = 'Young';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000012', id, 'draft', 2, 100 FROM golfers WHERE first_name = 'Ludvig' AND last_name = 'Aberg';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000012', id, 'draft', 3, 100 FROM golfers WHERE first_name = 'Michael' AND last_name = 'Kim';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000012', id, 'draft', 4, 100 FROM golfers WHERE first_name ILIKE 'Bud%' AND last_name = 'McCarty';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000012', id, 'draft', 5, 100 FROM golfers WHERE first_name = 'Tom' AND last_name = 'Hoge';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000012', id, 'draft', 6, 100 FROM golfers WHERE first_name = 'Denny' AND last_name = 'McCarthy';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000012', id, 'draft', 7, 100 FROM golfers WHERE first_name = 'Bud' AND last_name = 'Cauley';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000012', id, 'draft', 8, 100 FROM golfers WHERE first_name = 'Max' AND last_name = 'McGreevy';

-- Team OBAZ (draft order 13)
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000013', id, 'draft', 1, 100 FROM golfers WHERE first_name = 'Sepp' AND last_name = 'Straka';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000013', id, 'draft', 2, 100 FROM golfers WHERE first_name ILIKE 'Dav%' AND last_name ILIKE 'Hoe%';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000013', id, 'draft', 3, 100 FROM golfers WHERE first_name = 'Harry' AND last_name = 'Hall';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000013', id, 'draft', 4, 100 FROM golfers WHERE first_name = 'Kurt' AND last_name = 'Kitayama';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000013', id, 'draft', 5, 100 FROM golfers WHERE first_name ILIKE 'Ben%' AND last_name = 'Stevens';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000013', id, 'draft', 6, 100 FROM golfers WHERE first_name ILIKE 'Vin%' AND last_name = 'Whaley';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000013', id, 'draft', 7, 100 FROM golfers WHERE first_name = 'Matti' AND last_name = 'Schmid';
INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-000000000013', id, 'draft', 8, 100 FROM golfers WHERE first_name = 'Patrick' AND last_name = 'Fishburn';
