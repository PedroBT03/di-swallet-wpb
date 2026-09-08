# Verifier emulator

Minimal Flask app that emulates an OpenID4VP verifier for local lab runs. It serves authorization requests via `request_uri`, accepts `direct_post` callbacks, and simulates redirect returns.

## Run

Start WPB first (`./gradlew :app:bootRun` from the repository root), then:

```bash
cd verifier-emulator
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/python app.py
```

Default port: **8081** (`VERIFIER_PORT` to override). The lab frontend **Present** flow uses this process.

## Contents

- `app.py`: emulator
- `requests/`: example authorization requests (`direct_post.json`, redirect variants)
- `requests/conformance/`: DCQL fixtures for selective disclosure
- `trust/`: demo access certificate and signing key (see [`trust/README.md`](trust/README.md))

Requests are returned as ES256-signed JWTs with `verifier_info.x5c`. Dynamic DCQL: `GET /request/build/<claims>.json` (for example `/request/build/given_name,family_name.json`).

## Examples

```bash
curl http://localhost:8081/request/direct_post.json

curl -X POST http://localhost:8081/direct_post -H "Content-Type: application/json" \
  -d '{"state":"req-direct-post-1","nonce":"nonce-direct-123","vp_token":"STUB-VP"}'

curl "http://localhost:8081/redirect_return?state=req-redirect-query-1&code=demo_code"

curl http://localhost:8081/logs
```

If the wallet cannot resolve `request_uri`, confirm the emulator is listening on `localhost:8081`. Terminal logs and `/logs` include correlation ids.
