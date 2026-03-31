CREATE TABLE golfers (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pga_player_id TEXT UNIQUE,
    first_name    TEXT NOT NULL,
    last_name     TEXT NOT NULL,
    country       TEXT,
    world_ranking INT,
    active        BOOLEAN NOT NULL DEFAULT true,
    metadata      JSONB NOT NULL DEFAULT '{}',
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_golfers_ranking ON golfers(world_ranking);
CREATE INDEX idx_golfers_name ON golfers(last_name, first_name);
