-- Credential revocation references + fixed-capacity status list tracking.

ALTER TABLE status_lists
    ADD COLUMN IF NOT EXISTS capacity            INTEGER NOT NULL DEFAULT 131072,
    ADD COLUMN IF NOT EXISTS allocated_bitstring BYTEA   NOT NULL DEFAULT '\x';

ALTER TABLE wallet_credentials
    ADD COLUMN IF NOT EXISTS status_list_id       VARCHAR(255),
    ADD COLUMN IF NOT EXISTS status_list_index    INTEGER,
    ADD COLUMN IF NOT EXISTS issuer_status_uri    VARCHAR(512),
    ADD COLUMN IF NOT EXISTS issuer_status_index  INTEGER,
    ADD COLUMN IF NOT EXISTS revocation_state     VARCHAR(16) NOT NULL DEFAULT 'ACTIVE';
