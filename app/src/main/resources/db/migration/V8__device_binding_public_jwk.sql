-- Persist the full device public JWK so the WIA can attest the device (DPoP) key in its cnf claim,
-- keeping it distinct from the holder HSM key attested by the KA.
ALTER TABLE device_wallet_bindings
    ADD COLUMN device_public_jwk TEXT;
