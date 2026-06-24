import { useState } from "react";
import { Link } from "react-router-dom";
import { getWalletKey } from "../api/wallet";
import { wpbExternalBase } from "../api/client";
import { useAuth } from "../auth/AuthContext";
import { AuthenticatingBanner } from "../components/AuthenticatingBanner";
import { JsonPanel } from "../components/JsonPanel";
import { useAuthedApi } from "../hooks/useAuthedApi";
import { walletRpId, walletRpName } from "../auth/webauthn";
import type { WalletKeyRecord } from "../types/wallet";
import { formatApiError } from "../utils/apiError";

export function SettingsPage() {
  const { session, busy, error, clearError, unlock, reregisterPasskey, forgetDevice } = useAuth();
  const { withApiAuth } = useAuthedApi();
  const [testResult, setTestResult] = useState<WalletKeyRecord | null>(null);
  const [testError, setTestError] = useState<string | null>(null);

  const swaggerUrl = `${wpbExternalBase}/swagger-ui.html`;

  async function handleTestSession() {
    if (!session) {
      return;
    }
    clearError();
    setTestError(null);
    setTestResult(null);
    try {
      const key = await withApiAuth((headers) => getWalletKey(session.holderId, headers));
      setTestResult(key);
    } catch (err) {
      setTestError(formatApiError(err));
    }
  }

  async function handleTestSoleControl() {
    if (!session) {
      return;
    }
    clearError();
    setTestError(null);
    setTestResult(null);
    try {
      const headers = await unlock();
      const key = await getWalletKey(session.holderId, headers);
      setTestResult(key);
    } catch (err) {
      setTestError(formatApiError(err));
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
      <section className="page page--settings">
        <header className="page__header">
          <h1>Settings</h1>
          <p className="page__lead">Holder passkey and WebAuthn configuration for this lab UI.</p>
        </header>

        <div className="page-stack">
          <div className="card">
            <h2 className="card__title">Sign in required</h2>
            <p className="hint">
              Register or log in with a passkey before using protected wallet APIs.
            </p>
            <div className="toolbar privacy-card__toolbar">
              <Link className="button" to="/login">
                Log in
              </Link>
              <Link className="button button--secondary" to="/onboarding">
                New holder
              </Link>
              <a className="button button--secondary" href={swaggerUrl} target="_blank" rel="noreferrer">
                Open Swagger
              </a>
            </div>
          </div>
        </div>
      </section>
    );
  }

  return (
    <section className="page page--settings">
      <header className="page__header">
        <h1>Settings</h1>
        <p className="page__lead">
          Holder identity, WebAuthn RP configuration, and authentication checks for this browser.
        </p>
      </header>

      {busy ? <AuthenticatingBanner /> : null}
      {(error || testError) ? (
        <div className="alert alert--error" role="alert">
          {error ?? testError}
        </div>
      ) : null}

      <div className="page-stack">
        <div className="card">
          <h2 className="card__title">Holder &amp; WebAuthn</h2>
          <p className="hint">
            Wallet login passkey for WPB.
          </p>
          <dl className="details-list ops-trust-details">
            <div className="details-list__row">
              <dt>Holder id</dt>
              <dd>
                <code>{session.holderId}</code>
              </dd>
            </div>
            <div className="details-list__row">
              <dt>Credential id</dt>
              <dd>
                <code className="details-list__truncate" title={session.credentialId}>
                  {session.credentialId}
                </code>
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

        <div className="card">
          <h2 className="card__title">Authentication checks</h2>
          <p className="hint">
            Quick probes against <code>GET /api/v1/wallet/keys/{"{holderId}"}</code>. Use Swagger for
            full API exploration.
          </p>
          <ul className="settings-actions">
            <li className="settings-action">
              <span className="settings-action__title">Holder session</span>
              <p className="hint settings-action__hint">
                Calls <code>GET /keys</code> with the login session token.
              </p>
              <button
                type="button"
                className="button settings-action__btn"
                onClick={() => void handleTestSession()}
                disabled={busy}
              >
                Test holder session
              </button>
            </li>
            <li className="settings-action">
              <span className="settings-action__title">Sole control</span>
              <p className="hint settings-action__hint">
                Fresh WebAuthn assertion, same as consent, HSM sign, and revoke flows.
              </p>
              <button
                type="button"
                className="button settings-action__btn"
                onClick={() => void handleTestSoleControl()}
                disabled={busy}
              >
                {busy ? "Authenticating…" : "Test sole control"}
              </button>
            </li>
            <li className="settings-action">
              <span className="settings-action__title">Re-register passkey</span>
              <p className="hint settings-action__hint">
                After a WPB database reset or if this browser lost its device binding.
              </p>
              <button
                type="button"
                className="button settings-action__btn"
                onClick={() => void handleReregister()}
                disabled={busy}
              >
                Re-register passkey
              </button>
            </li>
            <li className="settings-action">
              <span className="settings-action__title">Forget passkey on this browser</span>
              <p className="hint settings-action__hint">
                Clears remembered holder and credential id in local storage only.
              </p>
              <button
                type="button"
                className="button button--danger settings-action__btn"
                onClick={forgetDevice}
                disabled={busy}
              >
                Forget passkey
              </button>
            </li>
            <li className="settings-action">
              <span className="settings-action__title">WPB API explorer</span>
              <p className="hint settings-action__hint">
                Open Swagger UI on the running backend ({wpbExternalBase}).
              </p>
              <a
                className="button settings-action__btn"
                href={swaggerUrl}
                target="_blank"
                rel="noreferrer"
              >
                Open Swagger
              </a>
            </li>
          </ul>

          {testResult ? (
            <>
              <p className="hint">
                <code>GET /api/v1/wallet/keys/{session.holderId}</code> succeeded.
              </p>
              <JsonPanel title="Wallet key response" data={testResult} defaultOpen />
            </>
          ) : (
            <p className="hint">Run a test above to verify session or sole-control authentication.</p>
          )}
        </div>
      </div>
    </section>
  );
}
