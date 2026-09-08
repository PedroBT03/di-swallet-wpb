# Demo scenarios

Catalog of lab flows. Assumes WPB on `:8080`, this UI on `:5173`, and PostgreSQL via `docker compose up -d` from the repository root.

## Prerequisites (all scenarios)

1. `./gradlew :app:bootRun` from the repository root (`dev` profile).
2. `cd wpi-dev && npm run dev`.
3. **Onboarding**: register a holder and passkey.
4. **Wallet**: provision the wallet unit (device bind, HSM key, WIA + KA).

---

## S1: Wallet core

| Step | Screen | Action |
|------|--------|--------|
| 1 | Wallet | Provision wallet unit |
| 2 | Issue | Issue demo PID (simulated CMD) |
| 3 | Wallet | Sign test payload |
| 4 | Wallet | Revoke credential, then Ops → Status lists → lookup index |
| 5 | Wallet | Delete from wallet (optional) |

## S2: OpenID4VP presentation

Requires the verifier emulator on `:8081`.

| Step | Screen | Action |
|------|--------|--------|
| 1 | Issue | Issue demo PID or mDL if needed |
| 2 | Present | Select PID or mDL → start presentation |
| 3 | Present | Approve consent (passkey) |
| 4 | Log | Open the new transaction |

## S3: OpenID4VCI issuance

| Step | Screen | Action |
|------|--------|--------|
| 1 | Issue | Start PID issuance |
| 2 | Issue | Continue → simulate CMD login → Continue |
| 3 | Issue | Approve storage consent |
| 4 | Wallet | Refresh: the new credential appears |

## S4: Deferred issuance

1. Enable `wpb.openid4vci.simulator.always-defer=true` on WPB (see the comment in `application-dev.properties`).
2. **Issue** → **PID: deferred issuance**.
3. Continue until `DEFERRED_PENDING`, then Continue again to poll until issued.
4. Complete storage consent and refresh the wallet.

## S5: Privacy

| Step | Screen | Action |
|------|--------|--------|
| 1 | Present | Complete S2 first (creates a log entry) |
| 2 | Privacy → Deletion | Load eligible → request deletion |
| 3 | Privacy → DPA | Load eligible → initiate report |

## S6: Ops and pseudonyms

| Step | Screen | Action |
|------|--------|--------|
| 1 | Ops → Trust mark | Load trust mark |
| 2 | Ops → Status lists | Load the published list; look up a revocation index from Wallet |
| 3 | Pseudonyms | Create a slot for `localhost` → register passkey |

## S7: Manual SD-JWT and wallet unit revoke

| Step | Screen | Action |
|------|--------|--------|
| 1 | Wallet | SD-JWT presentation: disclose selected claims |
| 2 | Wallet | Revoke wallet unit (cascades keys and credentials) |
| 3 | Ops | Confirm indices show revoked on the status list |

## Scenario data

| File | Purpose |
|------|---------|
| `src/scenarios/vciDemo.ts` | Pre-filled credential offers for Issue |
| `src/scenarios/vpDemo.ts` | Pre-filled request URIs for Present |
