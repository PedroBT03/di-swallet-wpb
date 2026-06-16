import { useState } from "react";
import { Link } from "react-router-dom";
import { getWalletKey } from "../api/wallet";
import { ApiError } from "../api/client";
import { useAuth } from "../auth/AuthContext";
import { walletRpId, walletRpName } from "../auth/webauthn";
import type { WalletKeySummary } from "../types/fido2";

export function SettingsPage() {
  const { session, busy, error, clearError, unlock, reregisterPasskey, signOut, forgetDevice } = useAuth();
  const [testResult, setTestResult] = useState<WalletKeySummary | null>(null);
  const [testError, setTestError] = useState<string | null>(null);

  async function handleTestAuth() {
    if (!session) return;
    clearError();
    setTestError(null);
    setTestResult(null);
    try {
      const headers = await unlock();
      const key = await getWalletKey(session.holderId, headers);
      setTestResult(key);
    } catch (err) {
      if (err instanceof ApiError) {
        setTestError(typeof err.body === "string" ? err.body : err.message);
      } else if (err instanceof Error) {
        setTestError(err.message);
      } else {
        setTestError("Authentication test failed.");
      }
    }
  }

  async function handleReregister() {
    clearError();
    setTestError(null);
    setTestResult(null);
    try {
      await reregisterPasskey();
    } catch {
      /* surfaced via context */
    }
  }

  if (!session) {
    return (
      <section className="page">
        <header className="page__header">
          <h1>Settings</h1>
          <p className="page__lead">Register a holder passkey before using protected wallet APIs.</p>
        </header>
        <div className="card card--muted">
          <p>
            <Link to="/login">Log in</Link> with an existing passkey, or{" "}
            <Link to="/onboarding">create a new holder</Link>.
          </p>
        </div>
      </section>
    );
  }

  return (
    <section className="page">
      <header className="page__header">
        <h1>Settings</h1>
        <p className="page__lead">
          Holder session, WebAuthn relying party configuration, and authentication checks.
        </p>
      </header>

      <div className="card">
        <dl className="details-list">
          <div className="details-list__row">
            <dt>Holder id</dt>
            <dd>
              <code>{session.holderId}</code>
            </dd>
          </div>
          <div className="details-list__row">
            <dt>Credential id</dt>
            <dd>
              <code className="details-list__truncate">{session.credentialId}</code>
            </dd>
          </div>
          <div className="details-list__row">
            <dt>RP ID</dt>
            <dd>
              <code>{walletRpId}</code>
            </dd>
          </div>
          <div className="details-list__row">
            <dt>RP name</dt>
            <dd>{walletRpName}</dd>
          </div>
          <div className="details-list__row">
            <dt>UI origin</dt>
            <dd>
              <code>{window.location.origin}</code>
            </dd>
          </div>
        </dl>
      </div>

      <div className="toolbar">
        <button type="button" onClick={() => void handleTestAuth()} disabled={busy}>
          {busy ? "Authenticating…" : "Test authentication (GET keys)"}
        </button>
        <button
          type="button"
          className="button button--secondary"
          onClick={() => void handleReregister()}
          disabled={busy}
        >
          Re-register passkey
        </button>
        <button type="button" className="button button--danger" onClick={signOut} disabled={busy}>
          Sign out
        </button>
        <button
          type="button"
          className="button button--danger"
          onClick={forgetDevice}
          disabled={busy}
        >
          Forget passkey on this browser
        </button>
      </div>

      {(error || testError) && (
        <div className="alert alert--error" role="alert">
          {error ?? testError}
        </div>
      )}

      {testResult && (
        <div className="card">
          <h2 className="card__title">Authenticated response</h2>
          <p className="hint">
            <code>GET /api/v1/wallet/keys/{session.holderId}</code> succeeded with{" "}
            <code>X-Wallet-Authorization</code>.
          </p>
          <pre className="raw-json__pre">{JSON.stringify(testResult, null, 2)}</pre>
        </div>
      )}
    </section>
  );
}
