# WPI Dev — lab frontend

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

Default credentials match `application.properties`: user `pedro`, password `tese2026`, database `diswallet`. If `bootRun` fails with *Connection to localhost:5432 refused*, the database is not running — start Docker Desktop (or the Docker daemon) and run the command above.

## Quick start

```bash
cd wpi-dev
npm install
npm run dev
```

Open [http://localhost:5173](http://localhost:5173). The **Health** page calls `GET /actuator/health` through the Vite proxy and should show **UP** when WPB is running.

### Holder passkey (phase 1)

1. **New holder** (header → *New holder*, or `/onboarding`) — pick a holder id and register a passkey.
2. **Log in** (header → *Log in*, or `/login`) — existing holder id + passkey unlock (no re-registration).
3. **Sign out** ends the UI session; **Log in** restores it using the passkey remembered on this browser.

If WebAuthn fails, confirm `http://localhost:5173` is listed in `wallet.origins` on WPB.

### Wallet dashboard

Open **Wallet** after signing in. Typical demo flow:

1. **Initialize wallet** — creates wallet unit + DPoP binding (needs FIDO2 device id from passkey registration).
2. **Ensure HSM key** — generates holder key in SoftHSM (or returns existing).
3. **Issue demo PID** — requires wallet state **OPERATIONAL** (or VALID) and an HSM key.
4. **Sign test** — remote signature inside the HSM.

Protected actions show an “Authenticating with passkey…” banner while WebAuthn runs.

On **Wallet**, use **Unlock & sync from server** once to load key and credentials (single passkey). **Refresh view** replays cached data without asking again. **Ensure HSM key** returns the existing key if one is already stored.

### OpenID4VP present

1. Start WPB with demo-mode: `./gradlew :app:bootRun --args='--wpb.openid4vp.demo-mode=true'`
2. Start the verifier emulator (`verifier-emulator/`). It signs requests with **ES256** and embeds the verifier **access certificate** in `verifier_info.x5c` (PKIX trust against `demo-lote.json` — not skipped).
2. Start the verifier emulator (`verifier-emulator/`, port **8081**).
3. On **Wallet**, issue a demo PID (PID scenarios need a matching credential).
4. Open **Present** — pick a demo scenario or paste a `request_uri`, then **Start presentation**.
5. Review the consent screen (claim paths only — no attribute values), then **Approve** or **Reject** (passkey).

Session state, `PresentationContext`, and audit events are shown at the bottom after the flow runs.

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
