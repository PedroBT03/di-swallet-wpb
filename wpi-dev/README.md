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
