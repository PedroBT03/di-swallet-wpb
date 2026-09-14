# DI-Swallet: Wallet Provider Backend (WPB)

Server-side **Wallet Provider Backend** for a research EU Digital Identity Wallet (DI-Swallet), developed at Instituto Superior Técnico.

The WPB hosts Wallet Instance logic, the WSCA/SCI boundary, and remote key use. The user device is a thin client. This repository is the prototype described in the author's MSc dissertation; it is **not** a fielded national wallet.

## Scope

Hexagonal Spring Boot service. External EUDI libraries sit behind domain-typed ports (`OpenId4VciGateway`, `OpenId4VpGateway`), with a simulated adapter for local runs and an SDK adapter for real endpoints.

| Piece | In this repository |
| --- | --- |
| WPI / PI | OpenID4VCI issuance and OpenID4VP presentation (REST + orchestrators) |
| Formats | Independent SD-JWT VC and ISO/IEC 18013-5 mdoc pipelines |
| WSCA / SCI | `HsmService`, SCI guard, FIDO2/WebAuthn assertion verification |
| Remote WSCD | SoftHSM2 PKCS#11 **software token** (substitutes a certified HSM; the SoftHSM2 software is used unmodified) |
| Lab UI | `wpi-dev` (Vite); not a production client |
| Verifier lab | `verifier-emulator` (Flask) for Present flows |

## Prerequisites

- Ubuntu 24.04 (or equivalent), OpenJDK 17, Docker, SoftHSM2 (`softhsm2`, `opensc`)
- Node.js 20+ and Python 3 only if you run the lab frontend and verifier emulator

## Setup

Initialize the SoftHSM2 token once:

```bash
mkdir -p ~/softhsm/tokens
echo "directories.tokendir = $HOME/softhsm/tokens" > ~/.softhsm2.conf
echo "objectstore.backend = file" >> ~/.softhsm2.conf
softhsm2-util --init-token --free --label "DI-Swallet-WSCD" --pin 1234 --so-pin 123456
export SOFTHSM2_CONF=$HOME/.softhsm2.conf
softhsm2-util --show-slots
```

Dev defaults in `app/src/main/resources/application.properties` / `application-dev.properties`:

```properties
wpb.hsm.library=/usr/lib/x86_64-linux-gnu/softhsm/libsofthsm2.so
wpb.hsm.pin=1234
```

`./gradlew :app:bootRun` and the test tasks set `SOFTHSM2_CONF` automatically. The PIN `1234` is a known-weak lab value; do not use it outside local/CI.

Copy `.env.example` to `.env` if you want Compose/Gradle to pick up local database overrides. `.env` is gitignored. Lab database defaults are `wpb` / `wpb-dev` / `diswallet`. If you previously created a Postgres volume with older credentials, recreate it:

```bash
docker compose down -v && docker compose up -d
```

## Run the local lab

Four processes, from the repository root. The `dev` profile uses an in-process simulated issuer; the Flask verifier is only needed for **Present**.

```bash
docker compose up -d          # PostgreSQL on localhost:5432
./gradlew :app:bootRun        # WPB on :8080 (Swagger at /swagger-ui.html)
```

```bash
cd verifier-emulator && python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/python app.py       # :8081
```

```bash
cd wpi-dev && npm install && npm run dev   # :5173
```

Open the frontend, register a passkey, provision a wallet, then **Issue** (simulated) and **Present** (`:8081`). Lab UI notes: [`wpi-dev/README.md`](wpi-dev/README.md).

Sensitive routes expect `X-Wallet-Authorization: fido2-assertion:<Base64URL_JSON>`. The interactive API catalogue is Swagger UI, not this README.

## Tests

SoftHSM2 is required for integration tests.

```bash
./gradlew :app:test
./gradlew :app:test :app:jacocoTestReport
# HTML report: app/build/reports/jacoco/test/html/index.html
./gradlew :app:conformanceTest
```

## Configuration

| Concern | Where |
| --- | --- |
| Database | `docker-compose.yml` / `.env.example` (`wpb` / `wpb-dev`); rejected by production-readiness checks |
| HSM | `wpb.hsm.library`, `wpb.hsm.pin`, SoftHSM2 token above |
| WebAuthn origins | `wallet.rp.*`, `wallet.origins` (localhost in `dev`) |
| Demo vs SDK adapters | `dev` profile enables OID4VCI/VP demo-mode |
| Lab trust material | `app/src/main/resources/trust/` and `verifier-emulator/trust/` (lab keys only; see that folder's README) |

## Documentation

Architecture, implementation, evaluation, and the full prototype-vs-production boundary are in the MSc dissertation (Instituto Superior Técnico). A public PDF/URL will be linked here when available.

## Prototype status

The backend is a thesis prototype: SoftHSM2 rather than a certified HSM/QSCD; simulated issuer/verifier adapters by default; FIDO2 registration without hardware attestation; issuance/presentation sessions in memory. Holder proofs are still signed inside the PKCS#11 token.

Do not deploy this configuration as a production wallet. Do not reuse the included lab database defaults, SoftHSM PIN, or test keys.

## Licence

This project's source is licensed under the [Apache License 2.0](LICENSE). Third-party dependencies remain under their own licences.
