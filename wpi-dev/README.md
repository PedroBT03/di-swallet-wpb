# WPI Dev: lab frontend

Visual test harness for the DI-Swallet WPB backend. Runs as a separate Node project (not part of the Gradle build).

## Prerequisites

- Node.js 20+
- **PostgreSQL** on port **5432** (via Docker Compose from the repo root)
- WPB running locally on port **8080** (`./gradlew :app:bootRun` from the repo root)

### Start PostgreSQL (required for WPB)

From the repository root:

```bash
docker compose up -d
```

Default credentials match `application.properties`: user `pedro`, password `tese2026`, database `diswallet`. If `bootRun` fails with *Connection to localhost:5432 refused*, the database is not running: start Docker Desktop (or the Docker daemon) and run the command above.

## Quick start

```bash
cd wpi-dev
npm install
npm run dev
```

Open [http://localhost:5173](http://localhost:5173). The **Health** page calls `GET /actuator/health` through the Vite proxy and should show **UP** when WPB is running.

### Holder passkey (phase 1)

1. **New holder** (header → *New holder*, or `/onboarding`): pick a holder id and register a passkey.
2. **Log in** (header → *Log in*, or `/login`): existing holder id + passkey unlock (no re-registration).
3. **Sign out** ends the UI session; **Log in** restores it using the passkey remembered on this browser.

If WebAuthn fails, confirm `http://localhost:5173` is listed in `wallet.origins` on WPB.

### Wallet dashboard

Open **Wallet** after signing in. Typical demo flow:

1. **Initialize wallet**: creates wallet unit + DPoP binding (needs FIDO2 device id from passkey registration).
2. **Ensure HSM key**: generates holder key in SoftHSM (or returns existing).
3. **Issue demo PID**: requires wallet state **OPERATIONAL** (or VALID) and an HSM key.
4. **Sign test**: remote signature inside the HSM.

Protected **sole-control** actions (consent approve/reject, HSM sign, revoke, delete) show an “Authenticating with passkey…” banner. Dashboard reads (wallet sync, consent **view**, transaction log) reuse the **holder session** opened at login (WIAM_15) and do not prompt again.

On **Wallet**, **Sync from server** loads key and credentials without a new passkey prompt after login. **Refresh view** replays cached data locally. **Ensure HSM key** creates or returns an HSM key and **does** require a fresh passkey (WIAM_14).

### OpenID4VP present

1. Start WPB with demo-mode: `./gradlew :app:bootRun --args='--wpb.openid4vp.demo-mode=true'`
2. Start the verifier emulator (`verifier-emulator/`, port **8081**). It signs requests with **ES256** and embeds the verifier **access certificate** in `verifier_info.x5c` (PKIX trust against `demo-lote.json`; not skipped).
3. On **Wallet**, issue a demo PID (PID scenarios) or driving licence (mDL scenarios).
4. Open **Present**, choose **PID** or **Driving licence (mDL)**, pick fields or a quick demo, then **Start presentation**.
5. Review the consent screen (claim paths only: no attribute values), then **Approve** or **Reject** (passkey).

Session state, `PresentationContext`, and audit events are shown at the bottom after the flow runs.

### OpenID4VCI issue (UC2)

1. Complete **UC1** first: **Onboarding** (passkey) → **Wallet** (init + HSM key).
2. WPB with simulated issuer: `wpb.openid4vci.demo-mode=true` (default in `dev` profile).
3. Open **Issue**: pick PID or mDL, then **Start issuance**.
4. **Continue** to prepare issuer authorization, then complete the **CMD** step (simulated citizen login).
5. **Continue** for credential request; the storage consent screen loads automatically. **Approve** or **Reject** with your passkey (WIAM_14 / ISSU_11).
6. Open **Wallet** → **Sync from server** to see the new credential.

WIA, KA, `IssuanceContext`, and audit events appear in **Developer details** on Issue and Wallet.

### Transaction log and privacy

1. After **Present** or **Issue** flows, open **Log** → **Load transactions** (uses holder session; no extra passkey).
2. Click a row to load the decrypted TS10 payload (holder `dek-mode` needs log passphrase first).
3. Select entries and **Download JWE** with an export password.
4. Open **Privacy** → load eligible presentations, then **Request deletion** or **Initiate report**.
5. Use **Copy** / **Open** on returned `mailto:`, `tel:`, or `https:` contact URIs.

**DPA report (demo):** local WPB uses a **dummy** DPA fallback (`dpa-demo@local.test`, labelled “CNPD (demo only)”) because emulator presentations do not store real supervisory-authority contacts and the RP registry is off by default. This lets you exercise mailto/actions in the lab only: configure real DPA contacts for production (see `application-dev.properties` comments and TS8).

When WPB runs with `wpb.transaction-log.dek-mode=holder`, derive the log key on **Log** before viewing detail or exporting.

### Ops (trust mark & status lists)

Open **Ops** (no passkey required):

1. **Trust mark**: loads `GET /api/v1/wallet/trust-mark`. Dev profile enables placeholder URLs; remote fetch warnings are expected.
2. **Status lists**: load the published JWT/JSON bitstring and look up a revocation index from Wallet (HSM key or credential).

### Pseudonyms

Open **Pseudonyms** after sign-in:

1. Create a slot with rpId `localhost` (must match the browser origin).
2. **Register passkey** runs a separate WebAuthn ceremony for that RP.
3. Requires `wpb.pseudonym.enabled=true` and `wpb.pseudonym.allowed-rp-ids=localhost` in `application-dev.properties`.

### Advanced wallet actions

- **SD-JWT presentation (manual)**: selective disclosure without a verifier session (`POST /credentials/{id}/presentation`).
- **Revoke wallet unit**: cascades revocation to keys and WP-managed credentials.
- **Deferred issuance**: on **Issue**, use the deferred scenario after enabling `wpb.openid4vci.simulator.always-defer=true`; Continue polls `POST /deferred/query`.

See [FEATURE_MATRIX.md](./FEATURE_MATRIX.md) and [scenarios/README.md](./scenarios/README.md) for full traceability and demo scripts.

### 15-minute thesis demo

1. Onboarding (UC1) → Wallet init → HSM key (~3 min)
2. Present (verifier emulator) → Log (~4 min)
3. Issue (UC2, CMD + PID) → Wallet sync (~4 min)
4. Privacy deletion + DPA report (~3 min)
5. Ops trust mark + status list lookup (~1 min)

### Delete wallet data (credentials)

On **Wallet**, each credential has **Delete from wallet** (permanent removal from WPB, logs `CredentialDeletion` in the transaction log) and **Revoke** (status-list invalidation). This is distinct from **Privacy → Data deletion**, which contacts the relying party about data they hold after a presentation.

- WPB Swagger (direct): [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)


## Proxy

The dev server proxies these paths to `http://localhost:8080` (override with `VITE_WPB_PROXY_TARGET` in `.env`):

| Path prefix   | Purpose        |
|---------------|----------------|
| `/api`        | Wallet REST    |
| `/openid4vp`  | OID4VP         |
| `/openid4vci` | OID4VCI        |
| `/actuator`   | Health/metrics |

This avoids CORS configuration on WPB during local development.

## WPB dev configuration

For **FIDO2 / WebAuthn** (phase 1+), the holder origin must be allowed by WPB. In `app/src/main/resources/application-dev.properties`:

```properties
wallet.origins=http://localhost,http://localhost:8080,http://localhost:5173,https://localhost
```

Without `http://localhost:5173`, passkey registration from this UI will be rejected.

## Build

```bash
npm run build    # output in dist/
npm run preview  # serve static build locally
```

## Roadmap

See [IMPLEMENTATION.md](./IMPLEMENTATION.md) for phased delivery (health → FIDO2 → wallet → OID4VP/OID4VCI → privacy → polish).
