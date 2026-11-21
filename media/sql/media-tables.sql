CREATE TABLE IF NOT EXISTS media_creators (
    id serial PRIMARY KEY,
    name text NOT NULL,
    discord_id text UNIQUE,
    discord_name text,
    minecraft_name text,
    channel_url text,
    cut_percent numeric(5,2) NOT NULL DEFAULT 10,
    window_days int NOT NULL DEFAULT 60,
    hold_days int NOT NULL DEFAULT 30,
    active boolean NOT NULL DEFAULT true,
    notes text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS media_hosts (
    host text PRIMARY KEY,
    creator_id int NOT NULL REFERENCES media_creators(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS media_rate_changes (
    id serial PRIMARY KEY,
    creator_id int NOT NULL REFERENCES media_creators(id) ON DELETE CASCADE,
    old_percent numeric(5,2),
    new_percent numeric(5,2) NOT NULL,
    changed_by text NOT NULL,
    reason text,
    changed_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS media_payments (
    id text PRIMARY KEY,
    player_uuid text,
    player_name text,
    amount numeric(12,2) NOT NULL,
    currency text NOT NULL,
    amount_usd numeric(12,2) NOT NULL,
    status text NOT NULL,
    packages text,
    paid_at timestamptz NOT NULL,
    synced_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS media_payments_player ON media_payments (player_uuid);

CREATE TABLE IF NOT EXISTS media_earnings (
    payment_id text PRIMARY KEY REFERENCES media_payments(id) ON DELETE CASCADE,
    creator_id int NOT NULL REFERENCES media_creators(id) ON DELETE CASCADE,
    player_uuid text NOT NULL,
    amount_usd numeric(12,2) NOT NULL,
    cut_percent numeric(5,2) NOT NULL,
    earning_usd numeric(12,2) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS media_earnings_creator ON media_earnings (creator_id);

CREATE TABLE IF NOT EXISTS media_payouts (
    id serial PRIMARY KEY,
    creator_id int NOT NULL REFERENCES media_creators(id) ON DELETE CASCADE,
    amount_usd numeric(12,2) NOT NULL,
    method text,
    note text,
    paid_by text NOT NULL,
    paid_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS media_applications (
    id serial PRIMARY KEY,
    kind text NOT NULL,
    status text NOT NULL DEFAULT 'pending',
    discord_id text NOT NULL,
    discord_name text NOT NULL,
    creator_id int REFERENCES media_creators(id) ON DELETE CASCADE,
    minecraft_name text,
    platform text,
    channel_url text,
    followers text,
    avg_viewers text,
    schedule text,
    content text,
    why text,
    current_percent numeric(5,2),
    requested_percent numeric(5,2),
    reviewed_by text,
    review_note text,
    granted_percent numeric(5,2),
    created_at timestamptz NOT NULL DEFAULT now(),
    reviewed_at timestamptz
);

CREATE INDEX IF NOT EXISTS media_applications_status ON media_applications (status, kind);

CREATE TABLE IF NOT EXISTS media_state (
    key text PRIMARY KEY,
    value text NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE media_creators ADD COLUMN IF NOT EXISTS code text;
CREATE UNIQUE INDEX IF NOT EXISTS media_creators_code ON media_creators (lower(code));
ALTER TABLE media_earnings ADD COLUMN IF NOT EXISTS source text NOT NULL DEFAULT 'link';

CREATE TABLE IF NOT EXISTS media_code_claims (
    basket text PRIMARY KEY,
    creator_id int NOT NULL REFERENCES media_creators(id) ON DELETE CASCADE,
    code text NOT NULL,
    player_uuid text NOT NULL,
    player_name text,
    complete boolean NOT NULL DEFAULT false,
    payment_id text,
    created_at timestamptz NOT NULL DEFAULT now(),
    checked_at timestamptz
);

CREATE INDEX IF NOT EXISTS media_code_claims_player ON media_code_claims (player_uuid, created_at);

ALTER TABLE media_applications ADD COLUMN IF NOT EXISTS offer_percent numeric(5,2);
ALTER TABLE media_applications ADD COLUMN IF NOT EXISTS offer_window_days int;
ALTER TABLE media_applications ADD COLUMN IF NOT EXISTS offer_hold_days int;
ALTER TABLE media_applications ADD COLUMN IF NOT EXISTS offer_name text;
ALTER TABLE media_applications ADD COLUMN IF NOT EXISTS offer_host text;
ALTER TABLE media_applications ADD COLUMN IF NOT EXISTS offer_code text;
ALTER TABLE media_applications ADD COLUMN IF NOT EXISTS offer_note text;
ALTER TABLE media_applications ADD COLUMN IF NOT EXISTS offered_by text;
ALTER TABLE media_applications ADD COLUMN IF NOT EXISTS offered_at timestamptz;
ALTER TABLE media_applications ADD COLUMN IF NOT EXISTS offer_final boolean NOT NULL DEFAULT false;
ALTER TABLE media_applications ADD COLUMN IF NOT EXISTS counter_percent numeric(5,2);
ALTER TABLE media_applications ADD COLUMN IF NOT EXISTS counter_note text;
ALTER TABLE media_applications ADD COLUMN IF NOT EXISTS countered_at timestamptz;
ALTER TABLE media_applications ADD COLUMN IF NOT EXISTS responded_at timestamptz;

ALTER TABLE media_creators ADD COLUMN IF NOT EXISTS activity_reset_at timestamptz;
UPDATE media_creators SET activity_reset_at = created_at WHERE activity_reset_at IS NULL;
ALTER TABLE media_creators ALTER COLUMN activity_reset_at SET DEFAULT now();
ALTER TABLE media_creators ADD COLUMN IF NOT EXISTS inactivity_warned_at timestamptz;
ALTER TABLE media_creators ADD COLUMN IF NOT EXISTS removed_at timestamptz;
ALTER TABLE media_creators ADD COLUMN IF NOT EXISTS removed_reason text;

CREATE TABLE IF NOT EXISTS media_content (
    id serial PRIMARY KEY,
    creator_id int NOT NULL REFERENCES media_creators(id) ON DELETE CASCADE,
    url text NOT NULL,
    title text,
    posted_on date NOT NULL,
    added_by text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS media_content_creator ON media_content (creator_id, posted_on DESC);
