-- Phase 8 Flyway baseline: canonical wallet lifecycle and binding aggregates.

CREATE TABLE wallet_units (
    id              BIGSERIAL PRIMARY KEY,
    wallet_id       VARCHAR(255) NOT NULL UNIQUE,
    holder_id       VARCHAR(255),
    state           VARCHAR(32)  NOT NULL,
    created_at      TIMESTAMP    NOT NULL
);

CREATE TABLE wallet_keys (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             VARCHAR(255) NOT NULL UNIQUE,
    key_alias           VARCHAR(255) NOT NULL UNIQUE,
    public_key_base64   TEXT,
    revocation_index    INTEGER      NOT NULL,
    created_at          TIMESTAMP    NOT NULL,
    wallet_unit_id      BIGINT REFERENCES wallet_units (id)
);

CREATE TABLE user_devices (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             VARCHAR(255) NOT NULL,
    credential_id       VARCHAR(255) NOT NULL UNIQUE,
    public_key_base64   TEXT         NOT NULL,
    user_handle         VARCHAR(255) NOT NULL,
    signature_count     BIGINT       NOT NULL,
    registered_at       TIMESTAMP    NOT NULL
);

CREATE TABLE device_wallet_bindings (
    id                      BIGSERIAL PRIMARY KEY,
    wallet_unit_id          BIGINT       NOT NULL REFERENCES wallet_units (id),
    binding_type            VARCHAR(32)  NOT NULL,
    device_key_thumbprint   VARCHAR(255) NOT NULL UNIQUE,
    state                   VARCHAR(32)  NOT NULL,
    bound_at                TIMESTAMP    NOT NULL,
    revoked_at              TIMESTAMP,
    user_device_id          BIGINT REFERENCES user_devices (id)
);

CREATE TABLE key_attestations (
    id                          BIGSERIAL PRIMARY KEY,
    wallet_unit_id              BIGINT       NOT NULL REFERENCES wallet_units (id),
    attestation_id              VARCHAR(255) NOT NULL UNIQUE,
    jwt                         TEXT         NOT NULL,
    key_storage                 VARCHAR(255) NOT NULL,
    status_list_uri             VARCHAR(255) NOT NULL,
    status_list_index           INTEGER      NOT NULL,
    technical_expires_at        TIMESTAMP    NOT NULL,
    status_maintenance_expires_at TIMESTAMP  NOT NULL,
    issued_at                   TIMESTAMP    NOT NULL,
    consumed_at                 TIMESTAMP,
    state                       VARCHAR(32)  NOT NULL
);

CREATE TABLE attested_keys (
    id                  BIGSERIAL PRIMARY KEY,
    key_attestation_id  BIGINT       NOT NULL REFERENCES key_attestations (id),
    key_id              VARCHAR(255) NOT NULL UNIQUE,
    key_alias           VARCHAR(255) NOT NULL UNIQUE,
    key_thumbprint      VARCHAR(255) NOT NULL UNIQUE,
    batch_position      INTEGER      NOT NULL,
    created_at          TIMESTAMP    NOT NULL,
    state               VARCHAR(32)  NOT NULL,
    wallet_key_id       BIGINT REFERENCES wallet_keys (id)
);

CREATE TABLE wallet_credentials (
    id                      BIGSERIAL PRIMARY KEY,
    user_id                 VARCHAR(255) NOT NULL,
    credential_type         VARCHAR(255) NOT NULL,
    encoded_data            TEXT         NOT NULL,
    encrypted_disclosures   TEXT         NOT NULL,
    wallet_key_id           BIGINT REFERENCES wallet_keys (id),
    issued_at               TIMESTAMP    NOT NULL
);

CREATE TABLE credential_key_bindings (
    id                  BIGSERIAL PRIMARY KEY,
    credential_id       BIGINT       NOT NULL UNIQUE REFERENCES wallet_credentials (id),
    attested_key_id     BIGINT       NOT NULL REFERENCES attested_keys (id),
    binding_format      VARCHAR(32)  NOT NULL,
    bound_at            TIMESTAMP    NOT NULL
);

CREATE TABLE status_lists (
    id          VARCHAR(255) PRIMARY KEY,
    bitstring   BYTEA        NOT NULL,
    next_index  INTEGER      NOT NULL
);

CREATE TABLE wia_status_indexes (
    id              BIGSERIAL PRIMARY KEY,
    holder_id       VARCHAR(255) NOT NULL,
    issuer_scope    VARCHAR(255) NOT NULL,
    list_id         VARCHAR(255) NOT NULL,
    status_index    INTEGER      NOT NULL,
    CONSTRAINT uk_wia_status_holder_scope UNIQUE (holder_id, issuer_scope)
);

CREATE INDEX idx_wallet_keys_wallet_unit_id ON wallet_keys (wallet_unit_id);
CREATE INDEX idx_device_wallet_bindings_wallet_unit_id ON device_wallet_bindings (wallet_unit_id);
CREATE INDEX idx_device_wallet_bindings_user_device_id ON device_wallet_bindings (user_device_id);
CREATE INDEX idx_attested_keys_wallet_key_id ON attested_keys (wallet_key_id);
CREATE INDEX idx_key_attestations_wallet_unit_id ON key_attestations (wallet_unit_id);
