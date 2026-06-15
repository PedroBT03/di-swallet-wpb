CREATE TABLE fido2_assertion_challenges (
    id                      BIGSERIAL PRIMARY KEY,
    user_id                 VARCHAR(255) NOT NULL,
    challenge_key           VARCHAR(512) NOT NULL,
    request_json            TEXT         NOT NULL,
    expires_at_epoch_millis BIGINT       NOT NULL,
    created_at_epoch_millis BIGINT       NOT NULL,
    CONSTRAINT uq_fido2_challenge_key UNIQUE (challenge_key)
);

CREATE INDEX idx_fido2_challenges_user_expires ON fido2_assertion_challenges (user_id, expires_at_epoch_millis);
