-- Phase 10: TS10 transaction log and credential device-bound flag

CREATE TABLE transaction_log_entries (
    id                  BIGSERIAL PRIMARY KEY,
    holder_id           VARCHAR(255) NOT NULL,
    transaction_id      VARCHAR(64)  NOT NULL,
    transaction_type    VARCHAR(64)  NOT NULL,
    transaction_result  VARCHAR(32)  NOT NULL,
    occurred_at         TIMESTAMP    NOT NULL,
    ts10_schema_version VARCHAR(16)  NOT NULL DEFAULT '1.2',
    payload_ciphertext  TEXT         NOT NULL,
    integrity_mac       VARCHAR(128) NOT NULL,
    deleted_by_user     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_transaction_log_transaction_id UNIQUE (transaction_id)
);

CREATE INDEX idx_tx_log_holder_occurred ON transaction_log_entries (holder_id, occurred_at DESC);
CREATE INDEX idx_tx_log_holder_deleted ON transaction_log_entries (holder_id, deleted_by_user);

ALTER TABLE wallet_credentials
    ADD COLUMN IF NOT EXISTS device_bound BOOLEAN NOT NULL DEFAULT TRUE;
