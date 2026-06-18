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
import { generateDevicePublicJwk } from "../crypto/deviceJwk";
import { loadWalletState, saveWalletState, summarizeCredential } from "../features/wallet/storage";
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
  const [presentationCredentialId, setPresentationCredentialId] = useState<number | "">("");
  const [claimsInput, setClaimsInput] = useState("given_name, family_name");
  const [presentationResult, setPresentationResult] = useState<PresentationResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);
  const [lastRaw, setLastRaw] = useState<unknown>(null);
  const presentationResultRef = useRef<HTMLDivElement | null>(null);

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
  }

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
      const previousAlias = cacheRef.current.key?.keyAlias;
      const wasRevoked = cacheRef.current.key?.revoked === true;
      const key = await withProtectedAction((headers) => createWalletKey(holderId, headers));
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
      const result = await withProtectedAction((headers) => revokeWalletKey(holderId, headers));
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
      const result = await withProtectedAction((headers) => deleteCredential(credentialId, headers));
      setLastRaw(result);
      await syncFromServer();
    });
  }

  async function handleRevokeCredential(credentialId: number) {
    await runAction(async () => {
      const result = await withProtectedAction((headers) => revokeCredential(credentialId, headers));
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
      const result = await withProtectedAction((headers) => revokeWalletUnit(walletId, headers));
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
      const result = await withProtectedAction((headers) =>
        createCredentialPresentation(credentialId, claims, headers),
      );
      setPresentationResult(result);
      setLastRaw(result);
      setSuccessMessage(
        `Built selective SD-JWT for credential #${credentialId} (${result.revealedClaims.length} claim(s)). ` +
          "This token is not sent to a verifier — use Present for a full OID4VP flow.",
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
        "This HSM key has been revoked and can no longer sign data. Unlock & sync to refresh status, or create a new key.",
      );
      return;
    }
    await runAction(async () => {
      const result = await withProtectedAction((headers) =>
        signData(holderId, signInput, headers),
      );
      setSignResult(result);
      setLastRaw(result);
    });
  }

  const keyRevoked = walletKey?.revoked === true;

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

        {successMessage && (
          <div className="alert alert--success" role="status">
            {successMessage}
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
            {walletState && (
              <div className="toolbar toolbar--compact">
                <button
                  type="button"
                  className="button button--danger"
                  disabled={busy || !walletState.walletId}
                  onClick={() => void handleRevokeWalletUnit()}
                >
                  Revoke wallet unit
                </button>
              </div>
            )}
            {walletState && <JsonPanel title="Init result" data={walletState} />}
          </section>

          <section className="card wallet-section">
            <h2 className="card__title">HSM key</h2>
            <p className="hint">
              Creates a new key when none exists. If the current key is revoked, Ensure issues a
              replacement and marks it active again.
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
                  <dt>Status</dt>
                  <dd>
                    <span
                      className={`status-badge status-badge--${keyRevoked ? "down" : "up"}`}
                    >
                      {keyRevoked ? "REVOKED" : "ACTIVE"}
                    </span>
                  </dd>
                </div>
                <div className="details-list__row">
                  <dt>Revocation index</dt>
                  <dd>{walletKey.revocationIndex}</dd>
                </div>
                <div className="details-list__row">
                  <dt>Public key</dt>
                  <dd>
                    <code
                      className="details-list__truncate"
                      title={walletKey.publicKeyBase64}
                    >
                      {walletKey.publicKeyBase64}
                    </code>
                  </dd>
                </div>
              </dl>
            ) : (
              <p className="hint">No key in cache. Use Unlock & sync or create a new HSM key.</p>
            )}
            {keyRevoked && (
              <div className="alert alert--info" role="status">
                This key is revoked on the status list. Signing and issuance are blocked until you create
                a new HSM key for this holder.
              </div>
            )}
            <div className="toolbar toolbar--compact">
              <button type="button" onClick={() => void handleCreateKey()} disabled={busy}>
                {walletKey ? "Ensure HSM key" : "Create HSM key"}
              </button>
              <button
                type="button"
                className="button button--danger"
                onClick={() => void handleRevokeKey()}
                disabled={busy || !walletKey || keyRevoked}
              >
                Revoke key
              </button>
            </div>
          </section>

          <section className="card wallet-section">
            <h2 className="card__title">Credentials</h2>
            <p className="hint">
              <strong>Delete from wallet</strong> removes the PID and encrypted payload from WPB
              storage. <strong>Revoke</strong> marks it invalid on the status list but keeps the
              row. To ask a verifier to erase data they received, use{" "}
              <Link to="/privacy">Privacy → Data deletion</Link>.
            </p>
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
                      <strong>{formatCredentialTypeLabel(credential.credentialType)}</strong>
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
                    <div className="toolbar toolbar--compact">
                      <button
                        type="button"
                        className="button button--danger button--sm"
                        disabled={busy}
                        onClick={() => void handleDeleteCredential(credential.id)}
                      >
                        Delete from wallet
                      </button>
                      <button
                        type="button"
                        className="button button--secondary button--sm"
                        disabled={busy || credential.revocationState === "REVOKED"}
                        onClick={() => void handleRevokeCredential(credential.id)}
                      >
                        Revoke
                      </button>
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </section>

          <section className="card wallet-section">
            <h2 className="card__title">SD-JWT builder (API lab)</h2>
            <p className="hint">
              <strong>Not the normal presentation path.</strong> In production, claim selection happens on{" "}
              <Link to="/present">Present</Link> — the verifier’s DCQL query defines which attributes are
              requested, and you approve them on the consent screen. This section only calls{" "}
              <code>POST /credentials/{"{id}"}/presentation</code> directly: it filters disclosures on a
              stored credential and returns a minimized SD-JWT string (no verifier, no vp_token).
            </p>
            {credentials.length === 0 ? (
              <p className="hint">Issue or sync credentials first.</p>
            ) : presentableCredentials.length === 0 ? (
              <p className="hint">
                All credentials are revoked. Revoked PIDs cannot be presented — issue a new demo PID or
                revoke only applies to status-list invalidation (row kept in wallet).
              </p>
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
                        #{credential.id} — {formatCredentialTypeLabel(credential.credentialType)}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="form__field">
                  <span className="form__label">Claims to disclose (comma-separated)</span>
                  <input
                    value={claimsInput}
                    onChange={(event) => setClaimsInput(event.target.value)}
                    disabled={busy}
                    spellCheck={false}
                    placeholder="given_name, family_name"
                  />
                </label>
                <p className="hint">
                  Demo PID claims include <code>given_name</code>, <code>family_name</code>,{" "}
                  <code>birthdate</code>, <code>nationality</code>, <code>address.locality</code>. Requires
                  passkey (same as Sign test).
                </p>
                <button type="submit" disabled={busy || presentableCredentials.length === 0}>
                  {busy ? "Authenticating…" : "Build SD-JWT"}
                </button>
              </form>
            )}
            {presentationResult ? (
              <div ref={presentationResultRef}>
                <p className="hint">
                  Revealed: <code>{presentationResult.revealedClaims.join(", ")}</code>
                </p>
                <code
                  className="credential-list__preview"
                  title={presentationResult.presentation}
                >
                  {presentationResult.presentation.length > 120
                    ? `${presentationResult.presentation.slice(0, 120)}…`
                    : presentationResult.presentation}
                </code>
                <JsonPanel title="Presentation result" data={presentationResult} defaultOpen />
              </div>
            ) : null}
          </section>

          <section className="card wallet-section">
            <h2 className="card__title">Sign test</h2>
            {keyRevoked && (
              <div className="alert alert--info" role="status">
                Signing is disabled because the HSM key is revoked.
              </div>
            )}
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
                disabled={busy || signInput.trim().length === 0 || keyRevoked}
              >
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
