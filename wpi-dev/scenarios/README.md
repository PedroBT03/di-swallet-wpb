# Demo scenarios catalog

Step-by-step flows for thesis demonstrations. Each scenario assumes WPB on `:8080`, wpi-dev on `:5173`, and PostgreSQL via `docker compose up -d`.

## Prerequisites (all scenarios)

1. `./gradlew :app:bootRun` from repo root (`dev` profile).
2. `cd wpi-dev && npm run dev`.
3. **Onboarding** — register a new holder + passkey.
4. **Wallet** — Initialize wallet unit → Ensure HSM key.

---

## S1 — Wallet core (~3 min)

| Step | Screen | Action |
|------|--------|--------|
| 1 | Wallet | Initialize wallet unit |
| 2 | Wallet | Ensure HSM key |
| 3 | Wallet | Issue demo PID |
| 4 | Wallet | Sign test payload |
| 5 | Wallet | Revoke credential → Ops → Status lists → lookup index |
| 6 | Wallet | Delete from wallet (optional) |

**Screenshots:** wallet grid with OPERATIONAL state; credential list with ACTIVE badge; signature JSON panel.

---

## S2 — OpenID4VP presentation (~4 min)

| Step | Screen | Action |
|------|--------|--------|
| 1 | Wallet | Issue demo PID if needed |
| 2 | Present | Use **PID selective** scenario → Start |
| 3 | Present | Approve consent (passkey) |
| 4 | Log | Unlock & load transactions → open row |

Requires verifier emulator on `:8081` and `wpb.openid4vp.demo-mode=true`.

---

## S3 — OpenID4VCI issuance (~4 min)

| Step | Screen | Action |
|------|--------|--------|
| 1 | Issue | **PID — pre-authorized** → Resolve offer |
| 2 | Issue | Continue through pre-auth (tx_code `1234`) |
| 3 | Issue | Unlock & load storage consent → Approve |
| 4 | Wallet | Unlock & sync — new credential appears |

---

## S4 — Deferred issuance (~3 min)

1. Enable on WPB: `wpb.openid4vci.simulator.always-defer=true` (see `application-dev.properties` comment).
2. **Issue** → **PID — deferred issuance** scenario.
3. Continue until state `DEFERRED_PENDING` → banner explains polling.
4. Continue again to poll `POST /deferred/query` until `DEFERRED_ISSUED`.
5. Complete storage consent and sync wallet.

---

## S5 — Privacy (~3 min)

| Step | Screen | Action |
|------|--------|--------|
| 1 | Present | Complete S2 first (creates log entry) |
| 2 | Privacy → Deletion | Load eligible → Request deletion → copy mailto |
| 3 | Privacy → DPA | Load eligible → Initiate report (uses dev DPA fallback) |

---

## S6 — Ops & pseudonyms (~2 min)

| Step | Screen | Action |
|------|--------|--------|
| 1 | Ops → Trust mark | Load trust mark (warnings OK with placeholder URLs) |
| 2 | Ops → Status lists | Load published list; lookup revocation index from Wallet |
| 3 | Pseudonyms | Create slot for `localhost` → Register passkey → list |

---

## S7 — Manual SD-JWT & wallet unit revoke (~2 min)

| Step | Screen | Action |
|------|--------|--------|
| 1 | Wallet | SD-JWT presentation — select credential, disclose `given_name, family_name` |
| 2 | Wallet | Revoke wallet unit (cascades keys/credentials) |
| 3 | Ops | Confirm indices show REVOKED on status list |

---

## Scripted scenario data

| File | Purpose |
|------|---------|
| `src/scenarios/vciDemo.ts` | Pre-filled credential offers for Issue page |
| `src/scenarios/vpDemo.ts` | Pre-filled request URIs for Present page |

## 15-minute thesis demo (compressed)

Run **S1** → **S2** → **S3** → **S5** → **S6** in order (~15 min with passkey prompts). Skip S4 unless deferred issuance is a talking point; mention S7 if discussing revocation depth.
