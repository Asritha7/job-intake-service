ALTER TABLE jobs DROP CONSTRAINT jobs_status_check;
ALTER TABLE jobs ADD COLUMN attempts integer NOT NULL DEFAULT 0;
ALTER TABLE jobs ADD COLUMN max_attempts integer NOT NULL DEFAULT 3;
ALTER TABLE jobs ADD COLUMN next_attempt_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE jobs ADD COLUMN lease_token uuid;
ALTER TABLE jobs ADD COLUMN lease_until timestamptz;
ALTER TABLE jobs ADD COLUMN result_sha256 text;
ALTER TABLE jobs ADD COLUMN last_error text;
ALTER TABLE jobs ADD CONSTRAINT jobs_status_check CHECK (status IN ('accepted','running','succeeded','failed'));
ALTER TABLE jobs ADD CONSTRAINT jobs_attempts_check CHECK (max_attempts BETWEEN 1 AND 10 AND attempts BETWEEN 0 AND max_attempts);
ALTER TABLE jobs ADD CONSTRAINT jobs_lease_check CHECK (
    (status = 'running' AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
    OR (status <> 'running' AND lease_token IS NULL AND lease_until IS NULL));
ALTER TABLE jobs ADD CONSTRAINT jobs_result_check CHECK (
    (status = 'succeeded' AND result_sha256 IS NOT NULL AND result_sha256 ~ '^[a-f0-9]{64}$')
    OR (status <> 'succeeded' AND result_sha256 IS NULL));
ALTER TABLE jobs ADD CONSTRAINT jobs_error_check CHECK (last_error IS NULL OR last_error IN ('execution_failed','lease_expired'));
CREATE INDEX jobs_claimable ON jobs(next_attempt_at, created_at) WHERE status IN ('accepted','running');
