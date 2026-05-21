from flask import Flask, request, jsonify, render_template_string
import json, os, uuid, logging
from datetime import datetime
import jwt

app = Flask(__name__)
BASE = os.path.dirname(__file__)
REQ_DIR = os.path.join(BASE, "requests")
LOGS = []

# Dummy key for JWT signing
SIGNING_KEY = "your-secret-key-for-jwt-signing-demo"

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
'''

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

@app.route("/health")
def health():
    return jsonify({"status":"ok","time":datetime.utcnow().isoformat()+"Z"})

@app.route("/requests/list")
def list_requests():
    files = []
    if os.path.isdir(REQ_DIR):
        files = [f for f in os.listdir(REQ_DIR) if f.endswith('.json')]
    return jsonify({"requests": files})

@app.route("/request/<name>")
def serve_request(name):
    path = os.path.join(REQ_DIR, name)
    if not os.path.isfile(path):
        return jsonify({"error":"not found"}), 404
    with open(path,'r') as f:
        data = json.load(f)
    # add a trace header for quick debugging
    correlation = str(uuid.uuid4())
    session = data.get('state') or str(uuid.uuid4())
    log(f"served authorization request {name}", session_id=session, correlation_id=correlation)
    
    # Encode as JWT
    try:
        jwt_token = jwt.encode(data, SIGNING_KEY, algorithm="HS256")
        resp_text = jwt_token if isinstance(jwt_token, str) else jwt_token.decode('utf-8')
        resp = app.response_class(
            response=resp_text,
            status=200,
            mimetype='application/jwt'  # Proper content-type for JWT
        )
        resp.headers['X-Correlation-Id'] = correlation
        return resp
    except Exception as e:
        app.logger.error(f"Failed to encode JWT: {e}")
        return jsonify({"error": "failed to encode JWT"}), 500

@app.route('/request/play')
def play_request():
    name = request.args.get('name') or 'direct_post.json'
    return serve_request(name)

@app.route('/direct_post', methods=['POST'])
def direct_post():
    payload = request.get_json(silent=True)
    corr = request.headers.get('X-Correlation-Id') or str(uuid.uuid4())
    session = (payload or {}).get('state') or request.args.get('state') or None
    log(f"received direct_post callback payload: keys={list((payload or {}).keys())}", session_id=session, correlation_id=corr)

    # basic validation
    errors = []
    if not payload:
        errors.append('no json payload')
    else:
        if 'state' not in payload:
            errors.append('missing state')

    if errors:
        log(f"direct_post validation failed: {errors}", session_id=session, correlation_id=corr)
        return jsonify({"status":"error","errors":errors}), 400

    # store event
    log(f"direct_post validated for state={payload.get('state')}", session_id=session, correlation_id=corr)
    return jsonify({"status":"ok","received_keys": list(payload.keys())})

@app.route('/redirect_return', methods=['GET','POST'])
def redirect_return():
    # Accept both query params and a POST body that simulates fragment data
    corr = request.headers.get('X-Correlation-Id') or str(uuid.uuid4())
    data = {}
    if request.method == 'GET':
        data = request.args.to_dict()
    else:
        data = request.get_json(silent=True) or request.form.to_dict()
    session = data.get('state')
    log(f"received redirect return: keys={list(data.keys())}", session_id=session, correlation_id=corr)

    # provide a simple HTML page showing received params
    return render_template_string('<h3>Redirect received</h3><pre>{{data}}</pre>', data=json.dumps(data, indent=2))

@app.route('/logs')
def get_logs():
    # return last 200 entries
    return jsonify({"count": len(LOGS), "logs": LOGS[-200:]})

@app.route('/')
def index():
    return HTML_PLAY

if __name__ == '__main__':
    port = int(os.environ.get('VERIFIER_PORT', '8081'))
    app.run(host='0.0.0.0', port=port)

