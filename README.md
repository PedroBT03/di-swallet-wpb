# DI-Swallet: Wallet Provider Backend (WPB)

Server-side implementation of the **Wallet Provider Backend (WPB)** for the EU Digital Identity
Wallet, developed for the **DI-Swallet** research project at **Instituto Superior Técnico**.

> **Thesis prototype.** This is a research backend, not a fielded system. Several integrations
> are simulated or gated for local use (see [Limitations](#limitations)).

## Architecture

Hexagonal (ports-and-adapters) Spring Boot service. Each external EUDI reference library is
reached only through a domain-typed **port**, implemented by a swappable **adapter** (a simulated
adapter for local/demo runs and an SDK-backed adapter for real integrations).

- **WPI** — REST API to the User Domain (issuance and presentation).
- **WSCA / WSCD** — hardware-backed cryptography via **SoftHSM2** over PKCS#11.
- **Security interceptor** — enforces sole-control (fresh FIDO2 assertion) on sensitive routes.
- **Flow orchestrators** — issuance (OpenID4VCI) and presentation (OpenID4VP) state machines.

## Tech Stack

- **Language:** Kotlin 2.3.0 · **Framework:** Spring Boot 4.1.1
- **Crypto:** BouncyCastle · PKCS#11 (SunPKCS11) · SoftHSM2
- **Persistence:** Spring Data JPA — PostgreSQL (runtime), H2 (tests), Flyway migrations

## Quickstart

### Prerequisites
- Ubuntu 24.04 · OpenJDK 17 (`sudo apt install openjdk-17-jdk`)
- SoftHSM2 (`sudo apt install softhsm2 opensc`) · Docker
- Node.js 20+ (lab frontend) · Python 3 (verifier emulator)

### 1. Initialize the SoftHSM2 token (once)
```bash
mkdir -p ~/softhsm/tokens
echo "directories.tokendir = $HOME/softhsm/tokens" > ~/.softhsm2.conf
echo "objectstore.backend = file" >> ~/.softhsm2.conf
softhsm2-util --init-token --free --label "DI-Swallet-WSCD" --pin 1234 --so-pin 123456
```

Confirm the token is visible:

```bash
export SOFTHSM2_CONF=$HOME/.softhsm2.conf
softhsm2-util --show-slots
```

Library path and PIN in `app/src/main/resources/application.properties` (dev default PIN is `1234`):
```properties
wpb.hsm.library=/usr/lib/x86_64-linux-gnu/softhsm/libsofthsm2.so
wpb.hsm.pin=1234
```

`./gradlew :app:bootRun` and tests set `SOFTHSM2_CONF` automatically. If the token fails to open: check the PIN (`CKR_PIN_INCORRECT`), the library path, or re-run the init command if the slot is empty.

### 2. Run the local lab (frontend)

Four processes, each in its own terminal, from the repository root. The default `dev` profile already enables OID4VCI and OID4VP demo-mode: issuance uses the **in-process simulated issuer**, so there is no separate issuer service. The Flask verifier is only needed for **Present**.

PostgreSQL:

```bash
docker compose up -d
```

WPB backend (HSM env handled by the Gradle build):

```bash
./gradlew :app:bootRun
```

Verifier emulator (OpenID4VP Present):

```bash
cd verifier-emulator
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/python app.py
```

Lab frontend:

```bash
cd wpi-dev
npm install
npm run dev
```

| Actor | URL |
| --- | --- |
| Frontend (wpi-dev) | http://localhost:5173 |
| WPB API / Swagger | http://localhost:8080/swagger-ui.html |
| Verifier emulator | http://localhost:8081 |
| PostgreSQL | localhost:5432 |

Open the frontend, register a passkey, provision a wallet, then use **Issue** (simulated issuer) and **Present** (verifier on `:8081`). Demo scripts and WebAuthn notes: [`wpi-dev/README.md`](wpi-dev/README.md).

### 3. Test
```bash
./gradlew clean test                              # full suite (SoftHSM2 required for integration tests)
./gradlew :app:test :app:jacocoTestReport         # + coverage → app/build/reports/jacoco/test/html/index.html
```

## API Reference

Protected endpoints require an `X-Wallet-Authorization: fido2-assertion:<Base64URL_JSON>` header.
Development flow: register a device (`POST /api/v1/wallet/auth/register/{userId}`), request a
challenge (`GET /api/v1/wallet/auth/challenge/{userId}`), sign it in the authenticator, and send
the assertion in the header.

| Action | Endpoint |
| --- | --- |
| Generate HSM-backed key | `POST /api/v1/wallet/keys/{userId}` |
| Retrieve key metadata | `GET /api/v1/wallet/keys/{userId}` |
| ECDSA signature (in-HSM) | `POST /api/v1/wallet/sign/{userId}` |

The full, interactive endpoint catalogue is available in Swagger UI.

## Features

- **Issuance (OpenID4VCI)** — credential-offer resolution, authorization-code and pre-authorized
  flows, deferred issuance, consent gating.
- **Presentation (OpenID4VP)** — DCQL matching, selective disclosure, encrypted responses.
- **Formats** — SD-JWT VC and ISO/IEC 18013-5 mdoc pipelines (dispatched by credential format).
- **Attestations** — Wallet Instance Attestation (WIA) and Key Attestation (KA).
- **Trust** — LoTE (ETSI TS 119 602) parsing, PKIX access-certificate validation, TS5 RP registry.
- **Lifecycle & keys** — wallet unit state machine, device binding, HSM key management.
- **Revocation** — IETF Token Status List publication and enforcement.
- **Privacy** — TS10 transaction log with holder-held keys, consent views, pseudonyms, data
  deletion and DPA reporting.
- **Operations** — production-readiness checks, Actuator health/metrics, conformance suite.

## Project Structure

```
app/                 Application code and tests (single Gradle module)
verifier-emulator/   Flask verifier for OpenID4VP Present flows
wpi-dev/             Lab frontend (Vite) and demo scenarios
docker-compose.yml   Local PostgreSQL
```

## Limitations

As a thesis prototype, this backend intentionally stops short of a fielded deployment. Key gaps:

- **WSCD:** SoftHSM2 is used in place of a certified HSM/QSCD, so LoA High / QES is targeted, not
  achieved.
- **Issuer/verifier:** simulated adapters are the default; real endpoints require the SDK adapters.
- **Device registration:** no hardware attestation (FIDO MDS) verification.
- **Sessions:** held in memory rather than a distributed store.

The design rationale and a full analysis of these limitations are covered in the dissertation.
