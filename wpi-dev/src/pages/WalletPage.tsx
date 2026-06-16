import { useCallback, useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import {
  createWalletKey,
  fetchWalletSummary,
  initWalletUnit,
  issueDemoSdCredential,
  revokeWalletKey,
  signData,
} from "../api/wallet";
import { AuthGate } from "../components/AuthGate";
import { AuthenticatingBanner } from "../components/AuthenticatingBanner";
import { JsonPanel } from "../components/JsonPanel";
import { generateDevicePublicJwk } from "../crypto/deviceJwk";
import { loadWalletState, saveWalletState, summarizeCredential } from "../features/wallet/storage";
import { mergeWalletStateFromSummary } from "../features/wallet/walletUnit";
import { useAuthedApi } from "../hooks/useAuthedApi";
import type {
  CredentialSummary,
  SignResult,
  WalletInitResult,
  WalletKeyRecord,
} from "../types/wallet";
import { formatApiError, isIssuanceEligible } from "../utils/apiError";

interface WalletCache {
  key: WalletKeyRecord | null;
  credentials: CredentialSummary[];
  syncedAt: string | null;
}

export function WalletPage() {
  const { session, withProtectedAction, busy, clearError } = useAuthedApi();
  const holderId = session?.holderId ?? "";

  const cacheRef = useRef<WalletCache>({
    key: null,
    credentials: [],
    syncedAt: null,
  });

  const [walletState, setWalletState] = useState<WalletInitResult | null>(null);
  const [platform, setPlatform] = useState("web");
  const [pidPubJwk, setPidPubJwk] = useState("");
  const [walletKey, setWalletKey] = useState<WalletKeyRecord | null>(null);
  const [credentials, setCredentials] = useState<CredentialSummary[]>([]);
  const [lastSyncedAt, setLastSyncedAt] = useState<string | null>(null);
  const [signInput, setSignInput] = useState("Hello from WPI Dev");
  const [signResult, setSignResult] = useState<SignResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [lastRaw, setLastRaw] = useState<unknown>(null);

  const applyCache = useCallback((cache: WalletCache) => {
    setWalletKey(cache.key);
    setCredentials(cache.credentials);
    setLastSyncedAt(cache.syncedAt);
  }, []);

  useEffect(() => {
    if (holderId) {
      setWalletState(loadWalletState(holderId));
    }
  }, [holderId]);

  const runAction = useCallback(
    async (action: () => Promise<void>) => {
      clearError();
      setError(null);
      try {
        await action();
      } catch (err) {
        setError(formatApiError(err));
      }
    },
    [clearError],
  );

  function refreshFromCache() {
    applyCache(cacheRef.current);
  }

  async function syncFromServer() {
    await runAction(async () => {
      const summary = await withProtectedAction((headers) =>
        fetchWalletSummary(holderId, headers),
      );
      const syncedAt = new Date().toISOString();
      cacheRef.current = {
        key: summary.key,
        credentials: summary.credentials,
        syncedAt,
      };
      applyCache(cacheRef.current);
      const mergedWallet = mergeWalletStateFromSummary(holderId, summary.walletUnit);
      if (mergedWallet) {
        setWalletState(mergedWallet);
      }
      setLastRaw(summary);
    });
  }

  async function handleInit(event: React.FormEvent) {
    event.preventDefault();
    await runAction(async () => {
      if (!session?.userDeviceId) {
        throw new Error(
          "FIDO2 device id is missing. Re-register your passkey from Settings, then try again.",
        );
      }
      const devicePubJwk = await generateDevicePublicJwk();
      const result = await initWalletUnit({
        holderId,
        platform,
        devicePubJwk,
        pidPubJwk: pidPubJwk.trim() || undefined,
        userDeviceId: session.userDeviceId,
      });
      setWalletState(result);
      saveWalletState(holderId, result);
      setLastRaw(result);
    });
  }

  async function handleCreateKey() {
    await runAction(async () => {
      const key = await withProtectedAction((headers) => createWalletKey(holderId, headers));
      cacheRef.current = {
        ...cacheRef.current,
        key,
        syncedAt: cacheRef.current.syncedAt,
      };
      setWalletKey(key);
      setLastRaw(key);
    });
  }

  async function handleRevokeKey() {
    await runAction(async () => {
      const result = await withProtectedAction((headers) => revokeWalletKey(holderId, headers));
      setLastRaw(result);
      await syncFromServer();
    });
  }

  async function handleIssueDemo() {
    if (!isIssuanceEligible(walletState?.state)) {
      setError(
        "Initialize the wallet unit first (state must be OPERATIONAL). Use Unlock & sync to refresh status.",
      );
      return;
    }
    if (!walletKey) {
      setError("Create or sync an HSM key before issuing a demo PID.");
      return;
    }
    await runAction(async () => {
      const issued = await withProtectedAction((headers) =>
        issueDemoSdCredential(holderId, headers),
      );
      setLastRaw(issued);
      const summary = summarizeCredential(issued);
      cacheRef.current = {
        ...cacheRef.current,
        credentials: [summary, ...cacheRef.current.credentials.filter((c) => c.id !== summary.id)],
        syncedAt: cacheRef.current.syncedAt,
      };
      setCredentials(cacheRef.current.credentials);
    });
  }

  async function handleSign(event: React.FormEvent) {
    event.preventDefault();
    await runAction(async () => {
      const result = await withProtectedAction((headers) =>
        signData(holderId, signInput, headers),
      );
      setSignResult(result);
      setLastRaw(result);
    });
  }

  const issuanceReady = isIssuanceEligible(walletState?.state);

  return (
    <AuthGate>
      <section className="page">
        <AuthenticatingBanner />
        <header className="page__header">
          <h1>Wallet</h1>
          <p className="page__lead">
            Holder <code>{holderId}</code> — initialize the wallet unit, manage HSM keys, issue demo
            credentials, and test remote signing.
          </p>
        </header>

        {error && (
          <div className="alert alert--error" role="alert">
            {error}
          </div>
        )}

        <div className="card card--muted wallet-sync-bar">
          <p>
            {lastSyncedAt
              ? `Last synced ${new Date(lastSyncedAt).toLocaleString()}`
              : "Not synced yet — unlock once to load key and credentials from WPB."}
          </p>
          <div className="toolbar toolbar--compact">
            <button type="button" onClick={() => void syncFromServer()} disabled={busy}>
              {busy ? "Authenticating…" : "Unlock & sync from server"}
            </button>
            <button
              type="button"
              className="button button--secondary"
              onClick={refreshFromCache}
              disabled={busy || !lastSyncedAt}
            >
              Refresh view
            </button>
          </div>
          <p className="hint">
            Refresh view replays cached data without passkey. Create, revoke, sign, and issue still
            require authentication.
          </p>
        </div>

        <div className="wallet-grid">
          <section className="card wallet-section">
            <h2 className="card__title">Home</h2>
            <dl className="details-list">
              <div className="details-list__row">
                <dt>Holder</dt>
                <dd>
                  <code>{holderId}</code>
                </dd>
              </div>
              <div className="details-list__row">
                <dt>Wallet unit</dt>
                <dd>{walletState?.walletId ?? "Not initialized"}</dd>
              </div>
              <div className="details-list__row">
                <dt>State</dt>
                <dd>{walletState?.state ?? "—"}</dd>
              </div>
              <div className="details-list__row">
                <dt>HSM key</dt>
                <dd>{walletKey?.keyAlias ?? "Not loaded"}</dd>
              </div>
              <div className="details-list__row">
                <dt>Credentials</dt>
                <dd>{credentials.length}</dd>
              </div>
            </dl>
            <div className="toolbar toolbar--compact">
              <Link className="button button--secondary" to="/settings">
                Settings
              </Link>
            </div>
          </section>

          <section className="card wallet-section">
            <h2 className="card__title">Initialize wallet unit</h2>
            <p className="hint">
              Binds a fresh DPoP device JWK to a new wallet unit. Does not require passkey (public
              bootstrap endpoint).
            </p>
            {!session?.userDeviceId && (
              <div className="alert alert--info">
                Missing device id — <Link to="/settings">re-register passkey</Link> to link init with
                FIDO2.
              </div>
            )}
            <form className="form" onSubmit={(event) => void handleInit(event)}>
              <label className="form__field">
                <span className="form__label">Platform</span>
                <input
                  value={platform}
                  onChange={(event) => setPlatform(event.target.value)}
                  disabled={busy}
                />
              </label>
              <label className="form__field">
                <span className="form__label">PID public JWK (optional)</span>
                <textarea
                  className="form__textarea"
                  value={pidPubJwk}
                  onChange={(event) => setPidPubJwk(event.target.value)}
                  rows={3}
                  placeholder='{"kty":"EC","crv":"P-256",...}'
                  disabled={busy}
                />
              </label>
              <button type="submit" disabled={busy || !session?.userDeviceId}>
                {busy ? "Working…" : "Initialize wallet"}
              </button>
            </form>
            {walletState && <JsonPanel title="Init result" data={walletState} />}
          </section>

          <section className="card wallet-section">
            <h2 className="card__title">HSM key</h2>
            <p className="hint">
              Create returns the existing key if one is already stored for this holder.
            </p>
            {walletKey ? (
              <dl className="details-list">
                <div className="details-list__row">
                  <dt>Alias</dt>
                  <dd>
                    <code>{walletKey.keyAlias}</code>
                  </dd>
                </div>
                <div className="details-list__row">
                  <dt>Revocation index</dt>
                  <dd>{walletKey.revocationIndex}</dd>
                </div>
                <div className="details-list__row">
                  <dt>Public key</dt>
                  <dd>
                    <code className="details-list__truncate">{walletKey.publicKeyBase64}</code>
                  </dd>
                </div>
              </dl>
            ) : (
              <p className="hint">No key in cache. Use Unlock & sync or create a new HSM key.</p>
            )}
            <div className="toolbar toolbar--compact">
              <button type="button" onClick={() => void handleCreateKey()} disabled={busy}>
                {walletKey ? "Ensure HSM key" : "Create HSM key"}
              </button>
              <button
                type="button"
                className="button button--danger"
                onClick={() => void handleRevokeKey()}
                disabled={busy || !walletKey}
              >
                Revoke key
              </button>
            </div>
          </section>

          <section className="card wallet-section">
            <h2 className="card__title">Credentials</h2>
            <p className="hint">Demo SD-JWT PID via mock issuer (dev profile only).</p>
            {!issuanceReady && (
              <div className="alert alert--info">
                Initialize the wallet unit before issuing. Current state:{" "}
                <code>{walletState?.state ?? "not initialized"}</code>.
              </div>
            )}
            {issuanceReady && !walletKey && (
              <div className="alert alert--info">
                Create or sync an HSM key before issuing a credential.
              </div>
            )}
            <div className="toolbar toolbar--compact">
              <button
                type="button"
                onClick={() => void handleIssueDemo()}
                disabled={busy || !issuanceReady || !walletKey}
              >
                Issue demo PID
              </button>
            </div>
            {credentials.length === 0 ? (
              <p className="hint">No credentials in cache for this holder.</p>
            ) : (
              <ul className="credential-list">
                {credentials.map((credential) => (
                  <li key={credential.id} className="credential-list__item">
                    <div className="credential-list__head">
                      <strong>{credential.credentialType}</strong>
                      <span
                        className={`status-badge status-badge--${credential.revocationState === "ACTIVE" ? "up" : "down"}`}
                      >
                        {credential.revocationState}
                      </span>
                    </div>
                    <div className="credential-list__meta">
                      <span>#{credential.id}</span>
                      <span>{credential.issuedAt}</span>
                      {credential.deviceBound && <span>device-bound</span>}
                    </div>
                    <code className="credential-list__preview">{credential.encodedPreview}</code>
                  </li>
                ))}
              </ul>
            )}
          </section>

          <section className="card wallet-section">
            <h2 className="card__title">Sign test</h2>
            <form className="form" onSubmit={(event) => void handleSign(event)}>
              <label className="form__field">
                <span className="form__label">Payload</span>
                <textarea
                  className="form__textarea"
                  value={signInput}
                  onChange={(event) => setSignInput(event.target.value)}
                  rows={3}
                  disabled={busy}
                />
              </label>
              <button type="submit" disabled={busy || signInput.trim().length === 0}>
                Sign in HSM
              </button>
            </form>
            {signResult && (
              <p className="hint">
                Algorithm <code>{signResult.algorithm}</code> — signature truncated below.
              </p>
            )}
            {signResult && <JsonPanel title="Signature result" data={signResult} defaultOpen />}
          </section>
        </div>

        {lastRaw != null && <JsonPanel title="Last API response (debug)" data={lastRaw} />}
      </section>
    </AuthGate>
  );
}
