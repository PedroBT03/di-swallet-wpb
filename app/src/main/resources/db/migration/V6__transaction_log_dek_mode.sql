-- Holder-held DEK mode for TS10 transaction log payloads (P1 / WIAM_12a)

ALTER TABLE transaction_log_entries
    ADD COLUMN IF NOT EXISTS dek_mode VARCHAR(16) NOT NULL DEFAULT 'SERVER';
