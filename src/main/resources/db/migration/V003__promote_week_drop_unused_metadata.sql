-- =============================================================
-- Tournament: promote metadata->>'week' to a proper column
-- =============================================================
ALTER TABLE tournaments ADD COLUMN week TEXT;
UPDATE tournaments SET week = metadata->>'week' WHERE metadata->>'week' IS NOT NULL;

-- =============================================================
-- Tournament results: expand round_scores JSONB into columns
-- =============================================================
ALTER TABLE tournament_results
  ADD COLUMN round1 INTEGER,
  ADD COLUMN round2 INTEGER,
  ADD COLUMN round3 INTEGER,
  ADD COLUMN round4 INTEGER;

UPDATE tournament_results SET
  round1 = (round_scores->>0)::int,
  round2 = (round_scores->>1)::int,
  round3 = (round_scores->>2)::int,
  round4 = (round_scores->>3)::int
WHERE round_scores IS NOT NULL AND round_scores != 'null'::jsonb;

ALTER TABLE tournament_results DROP COLUMN round_scores;

-- =============================================================
-- Fantasy scores: expand breakdown JSONB into columns
-- =============================================================
ALTER TABLE fantasy_scores
  ADD COLUMN position INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN num_tied INTEGER NOT NULL DEFAULT 1,
  ADD COLUMN base_payout NUMERIC(10,4) NOT NULL DEFAULT 0,
  ADD COLUMN ownership_pct NUMERIC(10,4) NOT NULL DEFAULT 100,
  ADD COLUMN payout NUMERIC(10,4) NOT NULL DEFAULT 0,
  ADD COLUMN multiplier NUMERIC(4,2) NOT NULL DEFAULT 1;

UPDATE fantasy_scores SET
  position    = COALESCE((breakdown->>'position')::int, 0),
  num_tied    = COALESCE((breakdown->>'num_tied')::int, 1),
  base_payout = COALESCE((breakdown->>'base_payout')::numeric, 0),
  ownership_pct = COALESCE((breakdown->>'ownership_pct')::numeric, 100),
  payout      = COALESCE((breakdown->>'payout')::numeric, 0),
  multiplier  = COALESCE((breakdown->>'multiplier')::numeric, 1)
WHERE breakdown IS NOT NULL AND breakdown != '{}'::jsonb;

ALTER TABLE fantasy_scores DROP COLUMN breakdown;

-- =============================================================
-- Seasons: expand rules JSONB into columns + join tables
-- =============================================================
ALTER TABLE seasons
  ADD COLUMN tie_floor NUMERIC(10,4) NOT NULL DEFAULT 1,
  ADD COLUMN side_bet_amount NUMERIC(10,4) NOT NULL DEFAULT 15;

UPDATE seasons SET
  tie_floor = COALESCE((rules->>'tie_floor')::numeric, 1),
  side_bet_amount = COALESCE((rules->>'side_bet_amount')::numeric, 15)
WHERE rules IS NOT NULL AND rules != '{}'::jsonb;

CREATE TABLE season_rule_payouts (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  season_id UUID NOT NULL REFERENCES seasons(id) ON DELETE CASCADE,
  position INTEGER NOT NULL,
  amount NUMERIC(10,4) NOT NULL,
  UNIQUE(season_id, position)
);

CREATE TABLE season_rule_side_bet_rounds (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  season_id UUID NOT NULL REFERENCES seasons(id) ON DELETE CASCADE,
  round INTEGER NOT NULL,
  UNIQUE(season_id, round)
);

-- Migrate existing payouts from JSONB array
INSERT INTO season_rule_payouts (season_id, position, amount)
SELECT s.id, ordinality, value::numeric
FROM seasons s, jsonb_array_elements_text(s.rules->'payouts') WITH ORDINALITY
WHERE s.rules->'payouts' IS NOT NULL AND jsonb_typeof(s.rules->'payouts') = 'array';

-- Migrate existing side bet rounds from JSONB array
INSERT INTO season_rule_side_bet_rounds (season_id, round)
SELECT s.id, value::int
FROM seasons s, jsonb_array_elements_text(s.rules->'side_bet_rounds') WITH ORDINALITY
WHERE s.rules->'side_bet_rounds' IS NOT NULL AND jsonb_typeof(s.rules->'side_bet_rounds') = 'array';

ALTER TABLE seasons DROP COLUMN rules;

-- =============================================================
-- Drop all remaining JSONB columns
-- =============================================================
ALTER TABLE tournaments DROP COLUMN metadata;
ALTER TABLE golfers DROP COLUMN metadata;
ALTER TABLE tournament_results DROP COLUMN metadata;
ALTER TABLE drafts DROP COLUMN settings;
