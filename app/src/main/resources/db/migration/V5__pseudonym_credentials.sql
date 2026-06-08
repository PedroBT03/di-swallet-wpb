CREATE TABLE pseudonym_credentials (
    id              UUID PRIMARY KEY,
    holder_id       VARCHAR(128) NOT NULL,
    rp_id           VARCHAR(256) NOT NULL,
    credential_id   VARCHAR(512),
    key_alias       VARCHAR(256),
    user_handle     VARCHAR(128) NOT NULL,
    alias           VARCHAR(256),
    sign_count      BIGINT NOT NULL DEFAULT 0,
    status          VARCHAR(32) NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_used_at    TIMESTAMP,
    CONSTRAINT chk_pseudonym_status CHECK (status IN ('PENDING', 'REGISTERED'))
);

CREATE INDEX idx_pseudonym_holder ON pseudonym_credentials (holder_id);
CREATE INDEX idx_pseudonym_holder_rp ON pseudonym_credentials (holder_id, rp_id);
CREATE UNIQUE INDEX uq_pseudonym_credential_id ON pseudonym_credentials (credential_id) WHERE credential_id IS NOT NULL;
CREATE UNIQUE INDEX uq_pseudonym_key_alias ON pseudonym_credentials (key_alias) WHERE key_alias IS NOT NULL;
