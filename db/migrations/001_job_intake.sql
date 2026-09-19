-- Initial schema. Application startup does not apply migrations.
CREATE TABLE IF NOT EXISTS intake_config (
    singleton boolean PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    capacity integer NOT NULL CHECK (capacity > 0)
);
INSERT INTO intake_config(singleton, capacity) VALUES (TRUE, 1000)
ON CONFLICT (singleton) DO NOTHING;
CREATE TABLE IF NOT EXISTS jobs (
    id uuid PRIMARY KEY,
    idempotency_key varchar(80) COLLATE "C" NOT NULL UNIQUE
        CHECK (idempotency_key ~ '^[A-Za-z0-9._-]{1,80}$'),
    payload text NOT NULL CHECK (octet_length(payload) BETWEEN 1 AND 8192),
    status text NOT NULL DEFAULT 'accepted' CHECK (status = 'accepted'),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
