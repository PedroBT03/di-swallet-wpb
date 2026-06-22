import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Link } from "react-router-dom";
import {
  createWalletKey,
  createCredentialPresentation,
  deleteCredential,
  fetchWalletSummary,
  initWalletUnit,
  issueDemoSdCredential,
  revokeCredential,
  revokeWalletKey,
  revokeWalletUnit,
  signData,
} from "../api/wallet";
import { AuthGate } from "../components/AuthGate";
import { AuthenticatingBanner } from "../components/AuthenticatingBanner";
import { JsonPanel } from "../components/JsonPanel";
import { ProtocolExchangePanel } from "../components/ProtocolExchangePanel";
import { Uc1Stepper } from "../components/wallet/Uc1Stepper";
import { generateDevicePublicJwk } from "../crypto/deviceJwk";
import { loadWalletState, saveWalletState, sortCredentialsByIssuedAt, summarizeCredential } from "../features/wallet/storage";
import { mergeWalletStateFromSummary } from "../features/wallet/walletUnit";
import { useAuthedApi } from "../hooks/useAuthedApi";
import type {
  CredentialSummary,
  PresentationResult,
  SignResult,
  WalletInitResult,
  WalletKeyRecord,
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
  const [pidPubJwk, setPidPubJwk] = useState("");
  const [walletKey, setWalletKey] = useState<WalletKeyRecord | null>(null);
  const [credentials, setCredentials] = useState<CredentialSummary[]>([]);
  const [lastSyncedAt, setLastSyncedAt] = useState<string | null>(null);
  const [signInput, setSignInput] = useState("Hello from WPI Dev");
  const [signResult, setSignResult] = useState<SignResult | null>(null);
  const [presentationCredentialId, setPresentationCredentialId] = useState<number | "">("");
  const [claimsInput, setClaimsInput] = useState("given_name, family_name");
  const [presentationResult, setPresentationResult] = useState<PresentationResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);
  const [lastRaw, setLastRaw] = useState<unknown>(null);
  const [lastInitRequest, setLastInitRequest] = useState<{
    platform: string;
    devicePubJwk: string;
    pidPubJwk?: string;
  } | null>(null);
  const presentationResultRef = useRef<HTMLDivElement | null>(null);
  const asideRef = useRef<HTMLElement | null>(null);
  const [asideHeight, setAsideHeight] = useState<number | null>(null);

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

  const presentableCredentials = useMemo(
    () => credentials.filter((credential) => credential.revocationState === "ACTIVE"),
    [credentials],
  );

  useEffect(() => {
    if (presentableCredentials.length === 0) {
      setPresentationCredentialId("");
      return;
    }
    setPresentationCredentialId((current) => {
      if (
        current !== "" &&
        presentableCredentials.some((credential) => credential.id === current)
      ) {
        return current;
      }
      return presentableCredentials[0]?.id ?? "";
    });
  }, [presentableCredentials]);

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

  function refreshFromCache() {
    applyCache(cacheRef.current);
  }

  async function performSync() {
    const summary = await withApiAuth((headers) => fetchWalletSummary(holderId, headers));
    const syncedAt = new Date().toISOString();
    cacheRef.current = {
      key: summary.key,
      credentials: sortCredentialsByIssuedAt(summary.credentials),
      syncedAt,
    };
    applyCache(cacheRef.current);
    const mergedWallet = mergeWalletStateFromSummary(holderId, summary.walletUnit);
    if (mergedWallet) {
      setWalletState(mergedWallet);
    }
    setLastRaw(summary);
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
      try {
        await performSyncRef.current();
      } catch (err) {
        if (!cancelled) {
          setError(formatApiError(err));
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [holderId, clearError]);

  async function syncFromServer() {
    await runAction(async () => {
      await performSync();
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
      const resolvedPidPub = pidPubJwk.trim() || (await generateDevicePublicJwk());
      const initPayload = {
        holderId,
        platform,
        devicePubJwk,
        pidPubJwk: resolvedPidPub,
        userDeviceId: session.userDeviceId,
      };
      setLastInitRequest({
        platform,
        devicePubJwk,
        pidPubJwk: resolvedPidPub,
      });
      const result = await initWalletUnit(initPayload);
      setWalletState(result);
      saveWalletState(holderId, result);
      setLastRaw(result);
    });
  }

  async function handleCreateKey() {
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
      setLastRaw(key);
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
      setLastRaw(result);
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
      const result = await withSoleControl((headers) => deleteCredential(credentialId, headers));
      setLastRaw(result);
      await syncFromServer();
    });
  }

  async function handleRevokeCredential(credentialId: number) {
    await runAction(async () => {
      const result = await withSoleControl((headers) => revokeCredential(credentialId, headers));
      setLastRaw(result);
      setSuccessMessage(
        result.walletLocalOnly
          ? `Credential #${credentialId} marked revoked in this wallet. The issuer status list was not updated (OID4VCI credential).`
          : `Credential #${credentialId} revoked on the wallet status list.`,
      );
      await syncFromServer();
    });
  }

  async function handleIssueDemo() {
    if (!isIssuanceEligible(walletState?.state)) {
      setError(
        "Initialize the wallet unit first (state must be OPERATIONAL). Use Sync from server to refresh status.",
      );
      return;
    }
    if (!walletKey) {
      setError("Create or sync an HSM key before issuing a demo PID.");
      return;
    }
    await runAction(async () => {
      const issued = await withSoleControl((headers) => issueDemoSdCredential(holderId, headers));
      setLastRaw(issued);
      const summary = summarizeCredential(issued);
      const merged = sortCredentialsByIssuedAt([
        ...cacheRef.current.credentials.filter((c) => c.id !== summary.id),
        summary,
      ]);
      cacheRef.current = {
        ...cacheRef.current,
        credentials: merged,
        syncedAt: cacheRef.current.syncedAt,
      };
      setCredentials(cacheRef.current.credentials);
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
      setLastRaw(result);
      await performSync();
    });
  }

  async function handleManualPresentation(event: React.FormEvent) {
    event.preventDefault();
    const credentialId = resolvePresentationCredentialId();
    if (credentialId == null) {
      setError("Select a credential for manual SD-JWT presentation.");
      return;
    }
    const claims = claimsInput
      .split(",")
      .map((claim) => claim.trim())
      .filter((claim) => claim.length > 0);
    if (claims.length === 0) {
      setError("Enter at least one claim name to disclose.");
      return;
    }
    await runAction(async () => {
      const result = await withSoleControl((headers) =>
        createCredentialPresentation(credentialId, claims, headers),
      );
      setPresentationResult(result);
      setLastRaw(result);
      setSuccessMessage(
        `Built selective SD-JWT for credential #${credentialId} (${result.revealedClaims.length} claim(s)). ` +
          "This token is not sent to a verifier. Use Present for a full OID4VP flow.",
      );
      window.requestAnimationFrame(() => {
        presentationResultRef.current?.scrollIntoView({ behavior: "smooth", block: "nearest" });
      });
    });
  }

  function resolvePresentationCredentialId(): number | null {
    if (presentationCredentialId !== "" && !Number.isNaN(Number(presentationCredentialId))) {
      return Number(presentationCredentialId);
    }
    const fallback = presentableCredentials[0]?.id;
    return fallback != null ? fallback : null;
  }

  async function handleSign(event: React.FormEvent) {
    event.preventDefault();
    if (walletKey?.revoked) {
      setError(
        "This HSM key has been revoked and can no longer sign data. Sync from server to refresh status, or create a new key.",
      );
      return;
    }
    await runAction(async () => {
      const result = await withSoleControl((headers) => signData(holderId, signInput, headers));
      setSignResult(result);
      setLastRaw(result);
    });
  }

  const keyRevoked = walletKey?.revoked === true;
  const issuanceReady = isIssuanceEligible(walletState?.state);
  const walletInitialized = issuanceReady && !!walletState?.walletId;
  const hsmReady = !!walletKey && !keyRevoked;
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

        <div className="wallet-hero">
          <div className="wallet-hero__main">
            <h1>Wallet</h1>
            <p className="wallet-hero__lead">
              Bootstrap the wallet unit: device binding, HSM key, and readiness for{" "}
              <Link to="/issue">credential issuance</Link>. Holder <code>{holderId}</code>.
            </p>
            <p className="wallet-hero__sync">
              {lastSyncedAt
                ? `Last synced ${new Date(lastSyncedAt).toLocaleString()}`
                : busy
                  ? "Syncing from server…"
                  : "Not synced yet."}
            </p>
          </div>
          <div className="wallet-hero__actions">
            <button type="button" onClick={() => void syncFromServer()} disabled={busy}>
              {busy ? "Syncing…" : "Sync from server"}
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
                Initialize wallet unit (<code>POST /wallet/init</code>)
              </li>
              <li className={`wallet-setup__step ${hsmReady ? "is-done" : ""}`}>
                <span className="wallet-setup__step-marker">{hsmReady ? "✓" : "3"}</span>
                Create or ensure HSM key
              </li>
              <li className={`wallet-setup__step ${credentials.length > 0 ? "is-done" : ""}`}>
                <span className="wallet-setup__step-marker">
                  {credentials.length > 0 ? "✓" : "4"}
                </span>
                <Link to="/issue">Issue a credential</Link>
              </li>
            </ol>
          </div>
        ) : null}

        <div
          className={`wallet-layout${asideHeight != null ? " wallet-layout--synced" : ""}`}
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
              <div className="wallet-panel__actions">
                <button
                  type="button"
                  onClick={() => void handleIssueDemo()}
                  disabled={busy || !issuanceReady || !walletKey || keyRevoked}
                >
                  Issue demo PID
                </button>
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
                Issue a demo PID or complete an <Link to="/issue">Issue</Link> flow, then sync.
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
                        disabled={busy || credential.revocationState === "REVOKED"}
                        onClick={() => void handleRevokeCredential(credential.id)}
                      >
                        Revoke
                      </button>
                      <button
                        type="button"
                        className="button button--danger button--sm"
                        disabled={busy}
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
                  </dl>
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
                    Binds <code>device_pub</code> (DPoP) and optional <code>pid_pub</code> (SCAL2 +
                    holder binding) to a new wallet unit. Does not require passkey.
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
                    <details>
                      <summary className="hint">Advanced: PID public JWK</summary>
                      <label className="form__field">
                        <textarea
                          className="form__textarea"
                          value={pidPubJwk}
                          onChange={(event) => setPidPubJwk(event.target.value)}
                          rows={3}
                          placeholder='{"kty":"EC","crv":"P-256",...}'
                          disabled={busy}
                        />
                      </label>
                    </details>
                    <button type="submit" disabled={busy || !session?.userDeviceId}>
                      {busy ? "Working…" : "Initialize wallet"}
                    </button>
                  </form>
                </>
              )}
            </section>

            <section className="card wallet-panel">
              <div className="wallet-panel__header">
                <h2 className="wallet-panel__title">HSM key</h2>
                <div className="wallet-panel__actions">
                  <button type="button" onClick={() => void handleCreateKey()} disabled={busy}>
                    {walletKey ? "Ensure key" : "Create key"}
                  </button>
                </div>
              </div>
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
                <p className="hint">Sync from server or create a key to enable signing and issuance.</p>
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
                  disabled={busy || !walletKey || keyRevoked}
                >
                  Revoke key
                </button>
              </div>
            </section>
          </aside>
        </div>

        <details className="wallet-advanced present-dev-details" open={!walletInitialized}>
          <summary>Developer details: wallet init protocol</summary>
          <div className="wallet-advanced__grid present-dev-details__body">
            <section className="wallet-panel">
              <h3 className="wallet-panel__title">Wallet init request</h3>
              <p className="hint">
                Lab mapping of <code>POST /wallet/init</code>. In production ARTE also validates Play
                Integrity / App Attest and issues WIA + WUA (phase <code>initial</code>).
              </p>
              {lastInitRequest ? (
                <JsonPanel title="Last init request" data={lastInitRequest} defaultOpen />
              ) : (
                <p className="hint">Initialize the wallet unit to capture the request payload.</p>
              )}
              {walletState ? (
                <JsonPanel title="Last init response" data={walletState} />
              ) : null}
              <ProtocolExchangePanel
                title="Production artefacts (reference)"
                summary="Not returned by this lab backend on init; shown for thesis traceability (wallet_init.md / ARF §6.5.3)."
                items={[
                  { label: "WIA", value: "JWT wallet instance attestation (24h TTL)", mono: false },
                  { label: "WUA initial", value: "JWT without pid_pub; used for first PID issuance", mono: false },
                  { label: "After wallet init", value: "operational (anonymous citizen)", mono: false },
                  { label: "After PID issuance", value: "valid + user_sub from CMD", mono: false },
                ]}
              />
            </section>

            <section className="wallet-panel">
              <h3 className="wallet-panel__title">SD-JWT builder (API lab)</h3>
              <p className="hint">
                Low-level <code>POST /credentials/{"{id}"}/presentation</code>. For real flows use{" "}
                <Link to="/present">Present</Link>.
              </p>
              {presentableCredentials.length === 0 ? (
                <p className="hint">No active credentials available.</p>
              ) : (
                <form className="form" onSubmit={(event) => void handleManualPresentation(event)}>
                  <label className="form__field">
                    <span className="form__label">Credential</span>
                    <select
                      value={presentationCredentialId === "" ? "" : String(presentationCredentialId)}
                      onChange={(event) =>
                        setPresentationCredentialId(
                          event.target.value === "" ? "" : Number(event.target.value),
                        )
                      }
                      disabled={busy}
                    >
                      {presentableCredentials.map((credential) => (
                        <option key={credential.id} value={credential.id}>
                          #{credential.id}: {formatCredentialTypeLabel(credential.credentialType)}
                        </option>
                      ))}
                    </select>
                  </label>
                  <label className="form__field">
                    <span className="form__label">Claims to disclose</span>
                    <input
                      value={claimsInput}
                      onChange={(event) => setClaimsInput(event.target.value)}
                      disabled={busy}
                      spellCheck={false}
                      placeholder="given_name, family_name"
                    />
                  </label>
                  <button type="submit" disabled={busy}>
                    {busy ? "Authenticating…" : "Build SD-JWT"}
                  </button>
                </form>
              )}
              {presentationResult ? (
                <div ref={presentationResultRef}>
                  <p className="hint">
                    Revealed: <code>{presentationResult.revealedClaims.join(", ")}</code>
                  </p>
                  <code className="credential-list__preview" title={presentationResult.presentation}>
                    {presentationResult.presentation.length > 120
                      ? `${presentationResult.presentation.slice(0, 120)}…`
                      : presentationResult.presentation}
                  </code>
                  <JsonPanel title="Presentation result" data={presentationResult} />
                </div>
              ) : null}
            </section>

            <section className="wallet-panel">
              <h3 className="wallet-panel__title">Sign test</h3>
              <p className="hint">Remote signature inside SoftHSM (requires active key).</p>
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
                <button type="submit" disabled={busy || signInput.trim().length === 0 || keyRevoked}>
                  Sign in HSM
                </button>
              </form>
              {signResult ? (
                <JsonPanel title="Signature result" data={signResult} defaultOpen />
              ) : null}
            </section>
          </div>
        </details>

        {lastRaw != null ? <JsonPanel title="Last API response (debug)" data={lastRaw} /> : null}
      </section>
    </AuthGate>
  );
}
