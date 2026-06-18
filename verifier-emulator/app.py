from flask import Flask, request, jsonify, render_template_string
import json
import logging
import os
import uuid
from datetime import datetime

import jwt

app = Flask(__name__)
BASE = os.path.dirname(__file__)
REQ_DIR = os.path.join(BASE, "requests")
TRUST_DIR = os.path.join(BASE, "trust")
LOGS = []

logging.basicConfig(level=logging.INFO, format='%(asctime)s %(levelname)s [%(message)s')

HTML_PLAY = '''
<!doctype html>
<title>Verifier Emulator - Play</title>
<h2>Verifier Emulator</h2>
<p>Available requests: <a href="/requests/list">list</a></p>
<form method="get" action="/request/play">
  <label>Request name: <input name="name" value="direct_post.json"></label>
  <button type="submit">Open request</button>
</form>
<p>Direct-post callback endpoint: <code>/direct_post</code></p>
<p>Redirect return endpoint: <code>/redirect_return</code></p>
<p>Logs: <a href="/logs">/logs</a></p>
<p>Trust material: <code>trust/demo-verifier-access.pem</code> (x5c in <code>verifier_info</code>)</p>
'''


def _pem_body_base64(pem_path: str) -> str:
    with open(pem_path, "r", encoding="utf-8") as handle:
        lines = [line.strip() for line in handle if line.strip() and not line.startswith("-----")]
    return "".join(lines)


def _load_trust_material() -> tuple[str, str, str]:
    access_pem = os.path.join(TRUST_DIR, "demo-verifier-access.pem")
    signing_key_path = os.path.join(TRUST_DIR, "demo-verifier.key")
    if not os.path.isfile(access_pem):
        raise FileNotFoundError(f"missing access certificate: {access_pem}")
    if not os.path.isfile(signing_key_path):
        raise FileNotFoundError(f"missing signing key: {signing_key_path}")
    with open(signing_key_path, "r", encoding="utf-8") as handle:
        signing_key = handle.read()
    x5c_b64 = _pem_body_base64(access_pem)
    return x5c_b64, signing_key, access_pem


try:
    VERIFIER_X5C_B64, REQUEST_SIGNING_KEY, _ACCESS_PEM_PATH = _load_trust_material()
except FileNotFoundError as exc:
    app.logger.error("Trust material not found: %s", exc)
    VERIFIER_X5C_B64 = ""
    REQUEST_SIGNING_KEY = ""


def log(event, session_id=None, correlation_id=None):
    entry = {
        "ts": datetime.utcnow().isoformat() + "Z",
        "id": str(uuid.uuid4()),
        "sessionId": session_id,
        "correlationId": correlation_id,
        "event": event,
    }
    LOGS.append(entry)
    app.logger.info(f"{entry['id']} session={session_id} corr={correlation_id} {event}")


def _with_verifier_info(data: dict) -> dict:
    enriched = dict(data)
    enriched["verifier_info"] = {"x5c": [VERIFIER_X5C_B64]}
    return enriched


@app.route("/health")
def health():
    trust_ok = bool(VERIFIER_X5C_B64 and REQUEST_SIGNING_KEY)
    return jsonify({
        "status": "ok" if trust_ok else "degraded",
        "trustMaterialLoaded": trust_ok,
        "time": datetime.utcnow().isoformat() + "Z",
    })


@app.route("/requests/list")
def list_requests():
    files = []
    if os.path.isdir(REQ_DIR):
        for root, _dirs, names in os.walk(REQ_DIR):
            for name in names:
                if name.endswith(".json"):
                    rel = os.path.relpath(os.path.join(root, name), REQ_DIR)
                    files.append(rel.replace(os.sep, "/"))
    return jsonify({"requests": sorted(files)})


@app.route("/request/<path:name>")
def serve_request(name):
    if not VERIFIER_X5C_B64 or not REQUEST_SIGNING_KEY:
        return jsonify({"error": "trust material not loaded; see verifier-emulator/trust/README.md"}), 500

    path = os.path.normpath(os.path.join(REQ_DIR, name))
    req_root = os.path.abspath(REQ_DIR)
    if not os.path.abspath(path).startswith(req_root + os.sep) and os.path.abspath(path) != req_root:
        return jsonify({"error": "not found"}), 404
    if not os.path.isfile(path):
        return jsonify({"error": "not found"}), 404
    with open(path, "r", encoding="utf-8") as handle:
        data = json.load(handle)

    data = _with_verifier_info(data)
    correlation = str(uuid.uuid4())
    session = data.get("state") or str(uuid.uuid4())
    log(
        f"served authorization request {name} with verifier_info.x5c",
        session_id=session,
        correlation_id=correlation,
    )

    try:
        jwt_token = jwt.encode(data, REQUEST_SIGNING_KEY, algorithm="ES256")
        resp_text = jwt_token if isinstance(jwt_token, str) else jwt_token.decode("utf-8")
        resp = app.response_class(
            response=resp_text,
            status=200,
            mimetype="application/oauth-authz-req+jwt",
        )
        resp.headers["X-Correlation-Id"] = correlation
        return resp
    except Exception as exc:
        app.logger.warning("ES256 JWT signing failed (%s); serving plain JSON authorization request", exc)
        resp = jsonify(data)
        resp.headers["X-Correlation-Id"] = correlation
        return resp


@app.route("/request/play")
def play_request():
    name = request.args.get("name") or "direct_post.json"
    return serve_request(name)


@app.route("/direct_post", methods=["POST"])
def direct_post():
    payload = request.get_json(silent=True)
    corr = request.headers.get("X-Correlation-Id") or str(uuid.uuid4())
    session = (payload or {}).get("state") or request.args.get("state") or None
    log(
        f"received direct_post callback payload: keys={list((payload or {}).keys())}",
        session_id=session,
        correlation_id=corr,
    )

    errors = []
    if not payload:
        errors.append("no json payload")
    elif "state" not in payload:
        errors.append("missing state")

    if errors:
        log(f"direct_post validation failed: {errors}", session_id=session, correlation_id=corr)
        return jsonify({"status": "error", "errors": errors}), 400

    log(f"direct_post validated for state={payload.get('state')}", session_id=session, correlation_id=corr)
    return jsonify({"status": "ok", "received_keys": list(payload.keys())})


@app.route("/redirect_return", methods=["GET", "POST"])
def redirect_return():
    corr = request.headers.get("X-Correlation-Id") or str(uuid.uuid4())
    if request.method == "GET":
        data = request.args.to_dict()
    else:
        data = request.get_json(silent=True) or request.form.to_dict()
    session = data.get("state")
    log(f"received redirect return: keys={list(data.keys())}", session_id=session, correlation_id=corr)
    return render_template_string("<h3>Redirect received</h3><pre>{{data}}</pre>", data=json.dumps(data, indent=2))


@app.route("/logs")
def get_logs():
    return jsonify({"count": len(LOGS), "logs": LOGS[-200:]})


@app.route("/")
def index():
    return HTML_PLAY


if __name__ == "__main__":
    port = int(os.environ.get("VERIFIER_PORT", "8081"))
    app.run(host="0.0.0.0", port=port)
