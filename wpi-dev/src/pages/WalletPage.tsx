import { useCallback, useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import {
  createWalletKey,
  deleteCredential,
  fetchWalletSummary,
  initWalletUnit,
  revokeCredential,
  revokeWalletKey,
  revokeWalletUnit,
  signData,
} from "../api/wallet";
import { AuthGate } from "../components/AuthGate";
import { AuthenticatingBanner } from "../components/AuthenticatingBanner";
import { JsonPanel } from "../components/JsonPanel";
import { MiddleTruncate } from "../components/MiddleTruncate";
import { Uc1Stepper } from "../components/wallet/Uc1Stepper";
import { WalletInitDevPanel, WalletProvisionStatus } from "../components/wallet/WalletProvisionOutcome";
import { generateDeviceKeyPair, saveDevicePrivateJwk } from "../crypto/deviceJwk";
import {
  loadWalletInitExchange,
  saveWalletInitExchange,
  type WalletInitExchange,
} from "../features/wallet/initExchange";
import { saveWalletState, sortCredentialsByIssuedAt } from "../features/wallet/storage";
import { mergeWalletStateFromSummary } from "../features/wallet/walletUnit";
import { useAuthedApi } from "../hooks/useAuthedApi";
import type {
  CredentialSummary,
  SignResult,
  WalletInitResult,
  WalletKeyRecord,
  WalletSummaryResponse,
} from "../types/wallet";
import { formatCredentialTypeLabel } from "../utils/credentialType";
import { formatApiError, isIssuanceEligible } from "../utils/apiError";

interface WalletCache {
  key: WalletKeyRecord | null;
  credentials: CredentialSummary[];
  syncedAt: string | null;
}

export function WalletPage() {
  const { session, withApiAuth, withSoleControl, busy, clearError } = useAuthedApi();
  const holderId = session?.holderId ?? "";

  const cacheRef = useRef<WalletCache>({
    key: null,
    credentials: [],
    syncedAt: null,
  });

  const [walletState, setWalletState] = useState<WalletInitResult | null>(null);
  const [platform, setPlatform] = useState("web");
  const [wiaJwt, setWiaJwt] = useState<string | null>(null);
  const [initExchange, setInitExchange] = useState<WalletInitExchange | null>(null);
  const [walletKey, setWalletKey] = useState<WalletKeyRecord | null>(null);
  const [credentials, setCredentials] = useState<CredentialSummary[]>([]);
  const [lastSyncedAt, setLastSyncedAt] = useState<string | null>(null);
  const [signInput, setSignInput] = useState("Hello from WPI Dev");
  const [signResult, setSignResult] = useState<SignResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);
  const [syncing, setSyncing] = useState(false);
  const asideRef = useRef<HTMLElement | null>(null);
  const [asideHeight, setAsideHeight] = useState<number | null>(null);

  const applyCache = useCallback((cache: WalletCache) => {
    setWalletKey(cache.key);
    setCredentials(cache.credentials);
    setLastSyncedAt(cache.syncedAt);
  }, []);

  useEffect(() => {
    if (!holderId) {
      setWalletState(null);
      setInitExchange(null);
      setWiaJwt(null);
      return;
    }
    const storedExchange = loadWalletInitExchange(holderId);
    setInitExchange(storedExchange);
    setWiaJwt(storedExchange?.response.wia ?? null);
  }, [holderId]);

  const runAction = useCallback(
    async (action: () => Promise<void>) => {
      clearError();
      setError(null);
      setSuccessMessage(null);
      try {
        await action();
      } catch (err) {
        setError(formatApiError(err));
      }
    },
    [clearError],
  );

  async function performSync(): Promise<WalletSummaryResponse | null> {
    if (!holderId) {
      return null;
    }
    const summary = await withApiAuth((headers) => fetchWalletSummary(holderId, headers));
    const syncedAt = new Date().toISOString();
    cacheRef.current = {
      key: summary.key,
      credentials: sortCredentialsByIssuedAt(summary.credentials),
      syncedAt,
    };
    applyCache(cacheRef.current);
    const mergedWallet = mergeWalletStateFromSummary(holderId, summary.walletUnit);
    setWalletState(mergedWallet);
    if (mergedWallet?.wia) {
      setWiaJwt(mergedWallet.wia);
    }
    return summary;
  }

  const performSyncRef = useRef(performSync);
  performSyncRef.current = performSync;

  useEffect(() => {
    if (!holderId) {
      return;
    }
    let cancelled = false;
    void (async () => {
      clearError();
      setError(null);
      setSuccessMessage(null);
      setSyncing(true);
      try {
        await performSyncRef.current();
      } catch (err) {
        if (!cancelled) {
          setError(formatApiError(err));
        }
      } finally {
        if (!cancelled) {
          setSyncing(false);
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [holderId, clearError]);

  async function refreshWalletData() {
    setSyncing(true);
    try {
      await performSync();
    } finally {
      setSyncing(false);
    }
  }

  async function handleRefresh() {
    await runAction(refreshWalletData);
  }

  async function handleInit(event: React.FormEvent) {
    event.preventDefault();
    await runAction(async () => {
      if (!session?.userDeviceId) {
        throw new Error(
          "FIDO2 device id is missing. Re-register your passkey from Settings, then try again.",
        );
      }
      const deviceKeyPair = await generateDeviceKeyPair();
      saveDevicePrivateJwk(holderId, deviceKeyPair.privateJwkJson);
      const initPayload = {
        holderId,
        platform,
        devicePubJwk: deviceKeyPair.publicJwkJson,
        userDeviceId: session.userDeviceId,
      };
      const result = await initWalletUnit(initPayload);
      const completedAt = new Date().toISOString();
      const exchange: WalletInitExchange = {
        completedAt,
        endpoint: "POST /api/v1/wallet/init",
        request: initPayload,
        response: result,
      };
      setInitExchange(exchange);
      saveWalletInitExchange(holderId, exchange);
      setWalletState(result);
      setWiaJwt(result.wia ?? null);
      saveWalletState(holderId, result);
      const summary = await performSync();
      if (summary) {
        const withSync: WalletInitExchange = {
          ...exchange,
          postSync: {
            completedAt: new Date().toISOString(),
            walletUnit: summary.walletUnit,
            key: summary.key,
          },
        };
        setInitExchange(withSync);
        saveWalletInitExchange(holderId, withSync);
      }
    });
  }

  async function handleCreateKey() {
    if (!walletInitialized) {
      setError("Initialize the wallet unit before creating an HSM key.");
      return;
    }
    await runAction(async () => {
      const previousAlias = cacheRef.current.key?.keyAlias;
      const wasRevoked = cacheRef.current.key?.revoked === true;
      const key = await withSoleControl((headers) => createWalletKey(holderId, headers));
      await performSync();
      if (wasRevoked || (previousAlias && previousAlias !== key.keyAlias)) {
        setSuccessMessage(
          "A new active HSM key replaced the revoked one. You can sign and issue credentials again.",
        );
      } else if (previousAlias) {
        setSuccessMessage("Existing HSM key is active and ready.");
      } else {
        setSuccessMessage("HSM key created successfully.");
      }
    });
  }

  async function handleRevokeKey() {
    await runAction(async () => {
      const result = await withSoleControl((headers) => revokeWalletKey(holderId, headers));
      if (cacheRef.current.key) {
        cacheRef.current = {
          ...cacheRef.current,
          key: { ...cacheRef.current.key, revoked: true },
        };
        setWalletKey(cacheRef.current.key);
      }
      await performSync();
      setSuccessMessage(
        `HSM key revoked successfully (status list index ${result.index}). ` +
          "Signing and issuance with this key are now blocked.",
      );
    });
  }

  async function handleDeleteCredential(credentialId: number) {
    if (!window.confirm(`Delete credential #${credentialId} from this wallet? This cannot be undone.`)) {
      return;
    }
    await runAction(async () => {
      await withSoleControl((headers) => deleteCredential(credentialId, headers));
      await refreshWalletData();
    });
  }

  async function handleRevokeCredential(credentialId: number) {
    await runAction(async () => {
      const result = await withSoleControl((headers) => revokeCredential(credentialId, headers));
      setSuccessMessage(
        result.walletLocalOnly
          ? `Credential #${credentialId} marked revoked in this wallet. The issuer status list was not updated (OID4VCI credential).`
          : `Credential #${credentialId} revoked on the wallet status list.`,
      );
      await refreshWalletData();
    });
  }

  async function handleRevokeWalletUnit() {
    const walletId = walletState?.walletId;
    if (!walletId) {
      setError("Initialize the wallet unit before revoking it.");
      return;
    }
    if (
      !window.confirm(
        `Revoke wallet unit ${walletId}? This cascades revocation to keys and WP-managed credentials.`,
      )
    ) {
      return;
    }
    await runAction(async () => {
      const result = await withSoleControl((headers) => revokeWalletUnit(walletId, headers));
      setSuccessMessage(`Wallet unit ${result.walletId} revoked.`);
      await performSync();
    });
  }

  async function handleSign(event: React.FormEvent) {
    event.preventDefault();
    if (!walletValid) {
      setError(
        "Holder signing is only available when the wallet is VALID. Identify with CMD on Issue first.",
      );
      return;
    }
    if (walletKey?.revoked) {
      setError(
        "This HSM key has been revoked and can no longer sign data. Use Refresh to update status, or create a new key.",
      );
      return;
    }
    await runAction(async () => {
      const result = await withSoleControl((headers) => signData(holderId, signInput, headers));
      setSignResult(result);
    });
  }

  const keyRevoked = walletKey?.revoked === true;
  const issuanceReady = isIssuanceEligible(walletState?.state);
  const walletInitialized = issuanceReady && !!walletState?.walletId;
  const walletValid = walletState?.state === "VALID";
  const hsmReady = !!walletKey && !keyRevoked;
  const canSign = walletValid && hsmReady;
  const setupComplete = walletInitialized && hsmReady;

  function formatIssuedAt(iso: string): string {
    const date = new Date(iso);
    if (Number.isNaN(date.getTime())) {
      return iso;
    }
    return date.toLocaleString();
  }

  function credentialInitials(type: string): string {
    const label = formatCredentialTypeLabel(type);
    if (label.length <= 3) {
      return label.toUpperCase();
    }
    return label.slice(0, 3).toUpperCase();
  }

  useEffect(() => {
    const el = asideRef.current;
    if (!el) {
      return undefined;
    }
    const updateHeight = () => setAsideHeight(el.offsetHeight);
    updateHeight();
    const observer = new ResizeObserver(updateHeight);
    observer.observe(el);
    return () => observer.disconnect();
  }, [walletState, walletKey, credentials.length, session?.userDeviceId]);

  return (
    <AuthGate>
      <section className="page page--wallet">
        <AuthenticatingBanner />

        {error ? (
          <div className="alert alert--error" role="alert">
            {error}
          </div>
        ) : null}

        {successMessage ? (
          <div className="alert alert--success" role="status">
            {successMessage}
          </div>
        ) : null}

        <div className={`wallet-hero${syncing ? " wallet-hero--refreshing" : ""}`}>
          <div className="wallet-hero__main">
            <h1>Wallet</h1>
            <p className="wallet-hero__lead">
              Bootstrap the wallet unit: device binding, HSM key, and readiness for{" "}
              <Link to="/issue">credential issuance</Link>. Holder <code>{holderId}</code>.
            </p>
            <p className="wallet-hero__sync">
              {syncing
                ? "Refreshing wallet data…"
                : lastSyncedAt
                  ? `Last refreshed ${new Date(lastSyncedAt).toLocaleString()}`
                  : "Not loaded yet."}
            </p>
          </div>
          <div className="wallet-hero__actions">
            <button
              type="button"
              className="button button--secondary"
              onClick={() => void handleRefresh()}
              disabled={busy || syncing}
            >
              {syncing ? (
                <>
                  <span className="btn-spinner" aria-hidden="true" />
                  Refreshing…
                </>
              ) : (
                "Refresh"
              )}
            </button>
          </div>
        </div>

        <Uc1Stepper
          passkeyDone={!!session?.userDeviceId}
          walletInitialized={walletInitialized}
          hsmReady={hsmReady}
        />

        <div className="wallet-stats">
          <div className="wallet-stat">
            <span className="wallet-stat__label">Wallet unit</span>
            <span className="wallet-stat__value">
              {walletState?.state ? (
                <span
                  className={`status-badge status-badge--${walletInitialized ? "up" : "unknown"}`}
                >
                  {walletState.state}
                </span>
              ) : (
                "Not initialized"
              )}
            </span>
          </div>
          <div className="wallet-stat">
            <span className="wallet-stat__label">HSM key</span>
            <span className="wallet-stat__value">
              {!walletKey ? (
                "Not loaded"
              ) : (
                <span className={`status-badge status-badge--${keyRevoked ? "down" : "up"}`}>
                  {keyRevoked ? "REVOKED" : "ACTIVE"}
                </span>
              )}
            </span>
          </div>
          <div className="wallet-stat">
            <span className="wallet-stat__label">Credentials</span>
            <span className="wallet-stat__value">{credentials.length}</span>
          </div>
        </div>

        {!setupComplete ? (
          <div className="wallet-setup">
            <h2 className="wallet-setup__title">Get started</h2>
            <ol className="wallet-setup__steps">
              <li className={`wallet-setup__step ${session?.userDeviceId ? "is-done" : ""}`}>
                <span className="wallet-setup__step-marker">{session?.userDeviceId ? "✓" : "1"}</span>
                <Link to="/onboarding">Register passkey</Link> (holder identity for this lab)
              </li>
              <li className={`wallet-setup__step ${walletInitialized ? "is-done" : ""}`}>
                <span className="wallet-setup__step-marker">{walletInitialized ? "✓" : "2"}</span>
                Provision wallet
              </li>
              <li
                className={`wallet-setup__step ${walletState?.state === "VALID" || credentials.length > 0 ? "is-done" : ""}`}
              >
                <span className="wallet-setup__step-marker">
                  {walletState?.state === "VALID" || credentials.length > 0 ? "✓" : "3"}
                </span>
                <Link to="/issue">Identify with CMD</Link> and issue PID
              </li>
              <li className={`wallet-setup__step ${credentials.length > 0 ? "is-done" : ""}`}>
                <span className="wallet-setup__step-marker">
                  {credentials.length > 0 ? "✓" : "4"}
                </span>
                Present or manage credentials
              </li>
            </ol>
          </div>
        ) : null}

        <div className="page-stack">
        <div
          className={`wallet-layout${asideHeight != null ? " wallet-layout--synced" : ""}${syncing ? " wallet-layout--refreshing" : ""}`}
          style={
            asideHeight != null
              ? ({ "--wallet-aside-height": `${asideHeight}px` } as React.CSSProperties)
              : undefined
          }
        >
          <section className="card wallet-panel wallet-panel--primary">
            <div className="wallet-panel__header">
              <div>
                <h2 className="wallet-panel__title">Credentials</h2>
                <p className="wallet-panel__subtitle">
                  Present via <Link to="/present">Present</Link> or issue on{" "}
                  <Link to="/issue">Issue</Link>. Data deletion requests are on{" "}
                  <Link to="/privacy">Privacy</Link>.
                </p>
              </div>
            </div>

            <div className="wallet-panel__body">
            {!issuanceReady ? (
              <div className="alert alert--info">
                Initialize the wallet unit before issuing. Current state:{" "}
                <code>{walletState?.state ?? "not initialized"}</code>.
              </div>
            ) : null}
            {issuanceReady && !walletKey ? (
              <div className="alert alert--info">
                Create or sync an HSM key before issuing a credential.
              </div>
            ) : null}

            {credentials.length === 0 ? (
              <div className="wallet-empty">
                <strong>No credentials yet</strong>
                Complete an <Link to="/issue">Issue</Link> flow, then refresh.
              </div>
            ) : (
              <ul className="wallet-credentials">
                {credentials.map((credential) => (
                  <li key={credential.id} className="wallet-credential">
                    <div className="wallet-credential__badge" aria-hidden>
                      {credentialInitials(credential.credentialType)}
                    </div>
                    <div className="wallet-credential__body">
                      <div className="wallet-credential__title">
                        {formatCredentialTypeLabel(credential.credentialType)}
                      </div>
                      <div className="wallet-credential__meta">
                        <span>#{credential.id}</span>
                        <span>{formatIssuedAt(credential.issuedAt)}</span>
                        {credential.deviceBound ? <span>Device-bound</span> : null}
                        <span
                          className={`status-badge status-badge--${credential.revocationState === "ACTIVE" ? "up" : "down"}`}
                        >
                          {credential.revocationState}
                        </span>
                      </div>
                    </div>
                    <div className="wallet-credential__actions">
                      <button
                        type="button"
                        className="button button--secondary button--sm"
                        disabled={busy || !walletInitialized || credential.revocationState === "REVOKED"}
                        onClick={() => void handleRevokeCredential(credential.id)}
                      >
                        Revoke
                      </button>
                      <button
                        type="button"
                        className="button button--danger button--sm"
                        disabled={busy || !walletInitialized}
                        onClick={() => void handleDeleteCredential(credential.id)}
                      >
                        Delete
                      </button>
                    </div>
                  </li>
                ))}
              </ul>
            )}
            </div>
          </section>

          <aside ref={asideRef} className="wallet-aside-stack">
            <section className="card wallet-panel">
              <h2 className="wallet-panel__title">Wallet unit</h2>
              {walletState?.walletId ? (
                <>
                  <dl className="wallet-key-meta">
                    <div className="wallet-key-meta__row">
                      <dt>State</dt>
                      <dd>
                        <span
                          className={`status-badge status-badge--${walletInitialized ? "up" : "unknown"}`}
                        >
                          {walletState.state}
                        </span>
                      </dd>
                    </div>
                    <div className="wallet-key-meta__row">
                      <dt>Unit id</dt>
                      <dd>
                        <code className="details-list__truncate" title={walletState.walletId}>
                          {walletState.walletId}
                        </code>
                      </dd>
                    </div>
                    {(walletState?.wia ?? wiaJwt) ? (
                      <div className="wallet-key-meta__row">
                        <dt>WIA</dt>
                        <dd>
                          <MiddleTruncate text={(walletState?.wia ?? wiaJwt)!} endChars={10} />
                        </dd>
                      </div>
                    ) : null}
                    {walletState?.ka ? (
                      <div className="wallet-key-meta__row">
                        <dt>KA</dt>
                        <dd>
                          <MiddleTruncate text={walletState.ka} endChars={10} />
                        </dd>
                      </div>
                    ) : null}
                  </dl>
                  <WalletProvisionStatus walletState={walletState} />
                  <div className="wallet-danger-zone">
                    <p className="wallet-danger-zone__label">Danger zone</p>
                    <button
                      type="button"
                      className="button button--danger button--sm"
                      disabled={busy}
                      onClick={() => void handleRevokeWalletUnit()}
                    >
                      Revoke wallet unit
                    </button>
                  </div>
                </>
              ) : (
                <>
                  <p className="hint">
                    Creates your wallet, binds this device, and issues a wallet attestation. State
                    stays <code>CANDIDATE</code> until you sign in with CMD on{" "}
                    <Link to="/issue">Issue</Link>.
                  </p>
                  {!session?.userDeviceId ? (
                    <div className="alert alert--info">
                      Missing device id.{" "}
                      <Link to="/settings">re-register passkey</Link> in Settings first.
                    </div>
                  ) : null}
                  <form className="form" onSubmit={(event) => void handleInit(event)}>
                    <label className="form__field">
                      <span className="form__label">Platform</span>
                      <input
                        value={platform}
                        onChange={(event) => setPlatform(event.target.value)}
                        disabled={busy}
                      />
                    </label>
                    <button type="submit" disabled={busy || !session?.userDeviceId}>
                      {busy ? "Working…" : "Provision wallet"}
                    </button>
                  </form>
                </>
              )}
            </section>

            <section className="card wallet-panel">
              <div className="wallet-panel__header">
                <h2 className="wallet-panel__title">HSM key</h2>
                <div className="wallet-panel__actions">
                  <button
                    type="button"
                    onClick={() => void handleCreateKey()}
                    disabled={busy || !walletInitialized}
                  >
                    {walletKey ? "Ensure key" : "Create key"}
                  </button>
                </div>
              </div>
              {!walletInitialized ? (
                <div className="alert alert--info">
                  Initialize the wallet unit before creating or using an HSM key.
                </div>
              ) : null}
              {walletKey ? (
                <dl className="wallet-key-meta">
                  <div className="wallet-key-meta__row">
                    <dt>Status</dt>
                    <dd>
                      <span className={`status-badge status-badge--${keyRevoked ? "down" : "up"}`}>
                        {keyRevoked ? "REVOKED" : "ACTIVE"}
                      </span>
                    </dd>
                  </div>
                  <div className="wallet-key-meta__row">
                    <dt>Alias</dt>
                    <dd>
                      <code>{walletKey.keyAlias}</code>
                    </dd>
                  </div>
                  <div className="wallet-key-meta__row">
                    <dt>Revocation index</dt>
                    <dd>{walletKey.revocationIndex}</dd>
                  </div>
                  <div className="wallet-key-meta__row">
                    <dt>Public key</dt>
                    <dd>
                      <code className="details-list__truncate" title={walletKey.publicKeyBase64}>
                        {walletKey.publicKeyBase64.slice(0, 16)}…
                      </code>
                    </dd>
                  </div>
                </dl>
              ) : (
                <p className="hint">Refresh or create a key to enable signing and issuance.</p>
              )}
              {keyRevoked ? (
                <div className="alert alert--info" role="status">
                  Key revoked on the status list. Ensure key to rotate and restore signing.
                </div>
              ) : null}
              <div className="wallet-danger-zone">
                <p className="wallet-danger-zone__label">Danger zone</p>
                <button
                  type="button"
                  className="button button--danger button--sm"
                  onClick={() => void handleRevokeKey()}
                  disabled={busy || !walletInitialized || !walletKey || keyRevoked}
                >
                  Revoke key
                </button>
              </div>
            </section>
          </aside>
        </div>

        <section className="card wallet-panel">
          <h2 className="wallet-panel__title">Sign test</h2>
          <p className="hint">Remote signature inside SoftHSM (requires VALID wallet and active key).</p>
          {!walletInitialized ? (
            <div className="alert alert--info">
              Provision the wallet unit before signing.
            </div>
          ) : null}
          {walletInitialized && !walletValid ? (
            <div className="alert alert--info">
              Wallet is <code>{walletState?.state}</code>. Identify with CMD on{" "}
              <Link to="/issue">Issue</Link> before using holder signing.
            </div>
          ) : null}
          {keyRevoked ? (
            <div className="alert alert--info" role="status">
              Signing disabled. Key is revoked.
            </div>
          ) : null}
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
            <button
              type="submit"
              disabled={busy || !canSign || signInput.trim().length === 0}
            >
              Sign in HSM
            </button>
          </form>
          {signResult ? <JsonPanel title="Signature result" data={signResult} defaultOpen /> : null}
        </section>
        <WalletInitDevPanel exchange={initExchange} walletState={walletState} />
        </div>
      </section>
    </AuthGate>
  );
}
