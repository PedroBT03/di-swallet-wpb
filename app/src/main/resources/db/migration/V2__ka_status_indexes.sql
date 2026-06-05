-- R11: durable KA status index mapping (symmetric with wia_status_indexes).

CREATE TABLE ka_status_indexes (
    id                      BIGSERIAL PRIMARY KEY,
    holder_id               VARCHAR(255) NOT NULL,
    issuer_scope            VARCHAR(255) NOT NULL,
    attestation_fingerprint VARCHAR(255) NOT NULL,
    list_id                 VARCHAR(255) NOT NULL,
    status_index            INTEGER      NOT NULL,
    CONSTRAINT uk_ka_status_holder_scope_fingerprint UNIQUE (holder_id, issuer_scope, attestation_fingerprint)
);

CREATE INDEX idx_ka_status_holder_id ON ka_status_indexes (holder_id);
