Verifier Emulator (minimal)

Overview

This small Flask app emulates a Verifier for OpenID4VP flows. It serves authorization requests via a `request_uri`, accepts `direct_post` callbacks, and simulates redirect returns. It's intentionally tiny for local protocol validation and debugging.

Files

- `app.py` - Flask emulator.
- `requests/` - example Authorization Requests (`direct_post.json`, `redirect_query.json`, `redirect_fragment.json`).

Quick start

1. From project root start the wallet (in separate terminal):

```bash
cd projects/di-swallet-wpb
./gradlew :app:bootRun
```

2. Start the verifier emulator (in another terminal):

```bash
cd projects/di-swallet-wpb/verifier-emulator
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/python app.py
```

Default port: `8081`. You can override with `VERIFIER_PORT` env var.

Example flows (curl)

- Serve a request_uri (the wallet will call this when resolving the request):

```bash
# The wallet will resolve http://localhost:8081/request/direct_post.json
curl http://localhost:8081/request/direct_post.json
```

- Simulate wallet dispatch (direct_post) to verifier:

```bash
curl -X POST http://localhost:8081/direct_post -H "Content-Type: application/json" \
  -d '{"state":"req-direct-post-1","nonce":"nonce-direct-123","vp_token":"STUB-VP"}'
```

- Simulate redirect return (query):

```bash
curl "http://localhost:8081/redirect_return?state=req-redirect-query-1&code=demo_code"
```

- Inspect logs/events received by emulator:

```bash
curl http://localhost:8081/logs | jq .
```

What to expect

- When wallet resolves a `request_uri`, the emulator logs an event with `served authorization request` and replies with the JSON request.
- For `direct_post`, the emulator logs the received JSON and validates basic fields (`state`).
- For redirect flows, the emulator shows the received query/POST body.

Troubleshooting

- If the wallet fails to resolve the `request_uri`, confirm the emulator is running and accessible on `localhost:8081`.
- Use `curl` to fetch `/request/<name>` and check `X-Correlation-Id` header for tracing.
- Check emulator logs printed to the terminal running `app.py` for correlation/session ids.

Next steps

- Hook wallet authorize flow using `request_uri` pointing to `http://localhost:8081/request/<file>.json`.
- Use `/logs` to follow lifecycle: resolution → matching → consent → dispatch → callback.
