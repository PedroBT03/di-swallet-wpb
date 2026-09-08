# WPI Dev: lab frontend

Visual test harness for the DI-Swallet WPB. It is a separate Node project (not part of the Gradle build) and is not a production wallet client.

## Prerequisites

- Node.js 20+
- PostgreSQL, WPB, and (for **Present**) the verifier emulator, as in the [repository README](../README.md)

## Quick start

```bash
cd wpi-dev
npm install
npm run dev
```

Open [http://localhost:5173](http://localhost:5173). The **Health** page calls `GET /actuator/health` through the Vite proxy and should show **UP** when WPB is running.

Passkey registration requires `http://localhost:5173` in `wallet.origins` on WPB (already set in the `dev` profile).

## Using the lab

1. **New holder** or **Log in**: register or unlock a holder passkey.
2. **Wallet**: provision the wallet unit (device bind, HSM key, WIA + KA).
3. **Issue**: start a simulated OID4VCI issuance (PID or mDL), complete the CMD step, then approve storage consent with the passkey.
4. **Present**: start the verifier emulator on port **8081**, choose PID or mDL, then approve or reject consent with the passkey.
5. **Log**, **Privacy**, **Ops**, and **Pseudonyms** exercise transaction history, deletion/DPA contacts, trust mark / status lists, and RP passkeys.

Sensitive actions (consent, sign, revoke, delete) prompt for a fresh passkey. Reads after login reuse the holder session.

Step-by-step demo flows: [`scenarios/README.md`](scenarios/README.md).

## Proxy

The Vite dev server proxies these paths to `http://localhost:8080` (override with `VITE_WPB_PROXY_TARGET` in `.env`):

| Path prefix   | Purpose        |
|---------------|----------------|
| `/api`        | Wallet REST    |
| `/openid4vp`  | OID4VP         |
| `/openid4vci` | OID4VCI        |
| `/actuator`   | Health/metrics |

## Build

```bash
npm run build    # output in dist/
npm run preview  # serve the static build locally
```
