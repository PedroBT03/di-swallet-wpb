import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { fetchWpbOperationalInfo, parseOpenId4VciDemoMode } from "../api/ops";
import {
  completeAuthorizationCode,
  completePreAuthorized,
  fetchIssuanceConsentView,
  fetchIssuanceEvents,
  fetchIssuanceSession,
  notifyIssuer,
  prepareAuthorization,
  queryDeferred,
  requestCredential,
  resolveOffer,
  submitIssuanceConsent,
} from "../api/openid4vci";
import { AuthGate } from "../components/AuthGate";
import { AuthenticatingBanner } from "../components/AuthenticatingBanner";
import { CmdIdentityPanel } from "../components/issue/CmdIdentityPanel";
import { IssuanceConsentScreen } from "../components/issue/IssuanceConsentScreen";
import { IssuanceStepper } from "../components/issue/IssuanceStepper";
import { JsonPanel } from "../components/JsonPanel";
import { ProtocolExchangePanel } from "../components/ProtocolExchangePanel";
import {
  MDL_ISSUANCE_CLAIMS,
  PID_ISSUANCE_CLAIMS,
} from "../features/issue/documentClaims";
import {
  availableDocuments,
  buildCredentialOfferUri,
  DEFAULT_ISSUANCE_GRANT,
  formatIssuanceLabel,
  getDocument,
} from "../features/issue/offerBuilder";
import {
  canContinueIssuance,
  continueActionLabel,
  flowKind,
  isTerminalIssuanceState,
  primaryCredentialConfigurationId,
  stateBadgeVariant,
} from "../features/issue/state";
import { useAuthedApi } from "../hooks/useAuthedApi";
import type { IssuanceConsentView, IssuanceContext, IssuanceEvent } from "../types/openid4vci";
import { signIssuanceWiaPop } from "../features/issue/wiaPop";
import { loadWalletState } from "../features/wallet/storage";
import { decodeJwtParts } from "../features/wallet/initExchange";
import { formatApiError } from "../utils/apiError";

const SIMULATED_AUTH_CODE = "code-abc";
const DOCUMENT_OPTIONS = availableDocuments();

function claimPreviewLabels(documentId: string): string {
  const claims = documentId === "mdl" ? MDL_ISSUANCE_CLAIMS : PID_ISSUANCE_CLAIMS;
  return claims.map((claim) => claim.label).join(", ");
}

export function IssuePage() {
  const { session, withApiAuth, withSoleControl, busy, clearError } = useAuthedApi();
  const holderId = session?.holderId.trim() ?? "";

  const [selectedDocumentId, setSelectedDocumentId] = useState("pid");
  const [offerUri, setOfferUri] = useState(
    buildCredentialOfferUri("pid_jwt", DEFAULT_ISSUANCE_GRANT),
  );
  const [context, setContext] = useState<IssuanceContext | null>(null);
  const [consentView, setConsentView] = useState<IssuanceConsentView | null>(null);
  const [events, setEvents] = useState<IssuanceEvent[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [resolving, setResolving] = useState(false);
  const [loadingConsent, setLoadingConsent] = useState(false);
  const [vciDemoMode, setVciDemoMode] = useState<boolean | null>(null);

  const selectedDocument = getDocument(selectedDocumentId);
  const sessionId = context?.sessionMeta.sessionId ?? null;
  const flowState = context?.state ?? consentView?.state ?? null;
  const showCmdStep = context?.state === "AUTHORIZATION_PREPARED";
  const showBuilder = context == null && consentView == null;
  const inProgress =
    context != null &&
    consentView == null &&
    !isTerminalIssuanceState(context.state) &&
    context.state !== "ISSUANCE_CONSENT_PENDING" &&
    !showCmdStep;
  const outcomeSuccess = flowState === "NOTIFIED";
  const outcomeFailed =
    flowState === "FAILED" || flowState === "REJECTED" || flowState === "EXPIRED";
  const issuanceSummary = formatIssuanceLabel(selectedDocumentId);

  const refreshVciDemoMode = useCallback(() => {
    fetchWpbOperationalInfo()
      .then((info) => setVciDemoMode(parseOpenId4VciDemoMode(info)))
      .catch(() => setVciDemoMode(null));
  }, []);

  useEffect(() => {
    refreshVciDemoMode();
    const onFocus = () => refreshVciDemoMode();
    window.addEventListener("focus", onFocus);
    const intervalId = window.setInterval(refreshVciDemoMode, 15_000);
    return () => {
      window.removeEventListener("focus", onFocus);
      window.clearInterval(intervalId);
    };
  }, [refreshVciDemoMode]);

  const syncBuilderOffer = useCallback((documentId: string) => {
    const document = getDocument(documentId);
    if (!document || document.labStatus !== "available") {
      return;
    }
    setOfferUri(buildCredentialOfferUri(document.configurationId, DEFAULT_ISSUANCE_GRANT));
  }, []);

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

  const loadConsentView = useCallback(
    async (id: string) => {
      const view = await withApiAuth((headers) => fetchIssuanceConsentView(id, holderId, headers));
      setConsentView(view);
    },
    [holderId, withApiAuth],
  );

  useEffect(() => {
    if (flowState !== "ISSUANCE_CONSENT_PENDING" || !sessionId || consentView) {
      return;
    }
    let cancelled = false;
    setLoadingConsent(true);
    clearError();
    setError(null);
    void (async () => {
      try {
        await loadConsentView(sessionId);
      } catch (err) {
        if (!cancelled) {
          setError(formatApiError(err));
        }
      } finally {
        if (!cancelled) {
          setLoadingConsent(false);
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [flowState, sessionId, consentView, loadConsentView, clearError]);

  const refreshDebug = useCallback(
    async (id: string) => {
      const [nextContext, nextEvents] = await withApiAuth(async (headers) => {
        const ctx = await fetchIssuanceSession(id, headers);
        const ev = await fetchIssuanceEvents(id, headers);
        return [ctx, ev] as const;
      });
      setContext(nextContext);
      setEvents(nextEvents);
    },
    [withApiAuth],
  );

  async function advanceIssuance(ctx: IssuanceContext): Promise<IssuanceContext> {
    const id = ctx.sessionMeta.sessionId;
    switch (ctx.state) {
      case "OFFER_RESOLVED":
        if (flowKind(ctx) === "PRE_AUTHORIZED_CODE") {
          if (!ctx.wia?.attestation) {
            return completePreAuthorized(id);
          }
          const preAuthPop = await signIssuanceWiaPop(holderId, ctx);
          return completePreAuthorized(id, undefined, preAuthPop);
        }
        if (!ctx.wia?.attestation) {
          return prepareAuthorization(id);
        }
        const authPop = await signIssuanceWiaPop(holderId, ctx);
        return prepareAuthorization(id, authPop);
      case "AUTHORIZATION_PREPARED": {
        const oauthState = ctx.preparedAuthorization?.state;
        if (!oauthState) {
          throw new Error("Authorization state is missing from the session.");
        }
        return completeAuthorizationCode(id, SIMULATED_AUTH_CODE, oauthState);
      }
      case "AUTHORIZED":
        return requestCredential(id, primaryCredentialConfigurationId(ctx));
      case "DEFERRED_PENDING":
        return queryDeferred(id);
      case "CREDENTIAL_ISSUED":
      case "DEFERRED_ISSUED":
        return notifyIssuer(id, "CREDENTIAL_ACCEPTED", "Stored in wallet");
      default:
        return ctx;
    }
  }

  async function handleStart(uri: string) {
    const trimmed = uri.trim();
    if (!trimmed) {
      setError("Credential offer URI is required.");
      return;
    }
    if (!holderId) {
      setError("Holder id is missing. Sign out and log in again.");
      return;
    }

    refreshVciDemoMode();
    setResolving(true);
    setConsentView(null);
    setEvents(null);
    setContext(null);
    setError(null);

    await runAction(async () => {
      const next = await resolveOffer({ offerUri: trimmed, holderId });
      setContext(next);
      if (isTerminalIssuanceState(next.state) && next.error) {
        setError(`${next.error.code}: ${next.error.message}`);
      }
    });

    setResolving(false);
  }

  function handleStartCustom() {
    if (!selectedDocument || selectedDocument.labStatus !== "available") {
      setError("This document type is not available in the lab yet.");
      return;
    }
    void handleStart(offerUri);
  }

  function handleReset() {
    setContext(null);
    setConsentView(null);
    setEvents(null);
    setError(null);
  }

  function selectDocument(documentId: string) {
    const document = getDocument(documentId);
    if (!document || document.labStatus !== "available") {
      return;
    }
    setError(null);
    setSelectedDocumentId(documentId);
    syncBuilderOffer(documentId);
  }

  async function handleContinue() {
    if (!context || !canContinueIssuance(context) || busy) {
      return;
    }
    await runAction(async () => {
      const next = await advanceIssuance(context);
      setContext(next);
      if (next.error) {
        setError(`${next.error.code}: ${next.error.message}`);
      }
    });
  }

  async function handleCmdContinue() {
    if (!context || context.state !== "AUTHORIZATION_PREPARED" || busy) {
      return;
    }
    await runAction(async () => {
      const next = await advanceIssuance(context);
      setContext(next);
      if (next.error) {
        setError(`${next.error.code}: ${next.error.message}`);
      }
    });
  }

  async function handleConsent(granted: boolean) {
    if (!sessionId) {
      return;
    }

    await runAction(async () => {
      const next = await withSoleControl((headers) =>
        submitIssuanceConsent(
          {
            sessionId,
            holderId,
            granted,
            reason: granted ? undefined : "Holder rejected credential storage",
          },
          headers,
        ),
      );
      setContext(next);
      setConsentView(null);
      setEvents(null);
    });
  }

  const wiaJwt = context?.wia?.attestation?.jwt ?? null;
  const issuanceKa = context?.ka?.attestation ?? null;
  const provisioningKaJwt = holderId ? loadWalletState(holderId)?.ka ?? null : null;
  const provisioningWiaJwt =
    holderId && !wiaJwt ? loadWalletState(holderId)?.wia ?? null : null;

  return (
    <AuthGate>
      <section className="page page--issue">
        <header className="present-hero">
          <div className="present-hero__main">
            <h1>Issue credentials</h1>
            <p className="present-hero__lead">
              Receive a PID or driving licence from the simulated issuer via OpenID4VCI. Citizen
              identity is established through <strong>CMD</strong> (simulated in this lab), then you
              approve storage with your passkey.
            </p>
          </div>
        </header>

        <AuthenticatingBanner />

        <div className="alert alert--info">
          <strong>Prerequisites</strong>
          <p>
            Complete <Link to="/onboarding">passkey registration</Link> and{" "}
            <Link to="/wallet">wallet init</Link> first. WPB uses the simulated issuer when{" "}
            <code>wpb.openid4vci.demo-mode=true</code>.
          </p>
        </div>

        {vciDemoMode === true ? (
          <div className="alert alert--info" role="status">
            WPB reports <code>openid4vci.demo-mode=true</code>. Simulated issuer flows are enabled.
          </div>
        ) : null}

        {vciDemoMode === false ? (
          <div className="alert alert--error" role="alert">
            <strong>WPB OpenID4VCI demo-mode is off</strong>
            <p>
              The running backend reports <code>operational.demoMode.openid4vci=false</code>.
              Restart WPB with{" "}
              <code>./gradlew :app:bootRun --args=&apos;--wpb.openid4vci.demo-mode=true&apos;</code>
            </p>
            <p className="hint">
              Check <Link to="/health">Backend health</Link>. The dev proxy targets{" "}
              <code>{import.meta.env.VITE_WPB_PROXY_TARGET ?? "http://localhost:8080"}</code>.
            </p>
          </div>
        ) : null}

        {error ? (
          <div className="alert alert--error" role="alert">
            {error}
          </div>
        ) : null}

        <IssuanceStepper state={flowState} />

        {showBuilder ? (
          <section className="card present-builder issue-builder">
            <h2 className="card__title">Which document do you want to receive?</h2>
            <p className="hint present-builder__lead">
              Choose <strong>PID</strong> (identity + mandatory metadata) or{" "}
              <strong>driving licence (mDL)</strong>, then start issuance.
            </p>

            <div className="present-claim-group">
              <div className="present-claim-toggles" role="group" aria-label="Document type">
                {DOCUMENT_OPTIONS.map((document) => {
                  const selected = selectedDocumentId === document.id;
                  const available = document.labStatus === "available";
                  return (
                    <button
                      key={document.id}
                      type="button"
                      className={[
                        "present-claim-toggle",
                        selected ? "present-claim-toggle--selected" : "",
                        !available ? "present-claim-toggle--unavailable" : "",
                      ]
                        .filter(Boolean)
                        .join(" ")}
                      aria-pressed={available ? selected : undefined}
                      aria-disabled={!available}
                      disabled={busy || resolving || vciDemoMode === false || !available}
                      title={!available ? document.unavailableNote : undefined}
                      onClick={() => selectDocument(document.id)}
                    >
                      <span className="present-claim-toggle__label">{document.label}</span>
                      <span className="present-claim-toggle__path">{document.description}</span>
                      {!available && document.unavailableNote ? (
                        <span className="present-claim-toggle__note">{document.unavailableNote}</span>
                      ) : null}
                    </button>
                  );
                })}
              </div>
            </div>

            {selectedDocumentId === "mdl" && selectedDocument?.labNote ? (
              <p className="hint issue-mdl-note">{selectedDocument.labNote}</p>
            ) : null}

            <details className="present-dev-details issue-claim-preview">
              <summary>Included attributes (reference)</summary>
              <p className="hint issue-claim-preview__text">{claimPreviewLabels(selectedDocumentId)}</p>
            </details>

            <div className="present-builder__summary">
              <span className="hint">You will request:</span>
              <strong>{issuanceSummary}</strong>
            </div>

            <button
              type="button"
              disabled={
                busy ||
                resolving ||
                vciDemoMode === false ||
                selectedDocument?.labStatus !== "available"
              }
              onClick={handleStartCustom}
            >
              {resolving ? "Starting…" : "Start issuance"}
            </button>

            <details className="present-dev-details issue-builder__advanced">
              <summary>Advanced: edit offer URI</summary>
              <div className="present-dev-details__body">
                <p className="hint">
                  Default grant is <code>authorization_code</code> (CMD + OAuth). A{" "}
                  <code>pre-authorized_code</code> offer is available for adapter testing only.
                </p>
                <label className="form__field">
                  <span className="form__label">credential_offer_uri</span>
                  <textarea
                    className="form__textarea"
                    value={offerUri}
                    onChange={(e) => setOfferUri(e.target.value)}
                    rows={4}
                    disabled={busy || resolving}
                    spellCheck={false}
                  />
                </label>
              </div>
            </details>
          </section>
        ) : null}

        {showCmdStep && context ? (
          <CmdIdentityPanel
            busy={busy}
            onContinue={() => void handleCmdContinue()}
            onCancel={handleReset}
          />
        ) : null}

        {inProgress && context ? (
          <section className="card present-unlock issue-progress">
            <h2 className="card__title">Issuance in progress</h2>
            <p className="present-unlock__lead">
              Requesting <strong>{issuanceSummary}</strong>. Continue the issuer steps below, then
              approve storage when prompted.
            </p>

            {context.state === "DEFERRED_PENDING" ? (
              <div className="alert alert--info" role="status">
                <strong>Deferred issuance</strong>
                <p>
                  The simulated issuer returned a transaction id. Click <strong>Continue</strong> to
                  poll until the credential is ready.
                </p>
                {context.deferredHandle?.transactionId ? (
                  <p className="hint">
                    Transaction id: <code>{context.deferredHandle.transactionId}</code>
                  </p>
                ) : null}
              </div>
            ) : null}

            <dl className="details-list">
              {sessionId ? (
                <div className="details-list__row">
                  <dt>Session</dt>
                  <dd>
                    <code className="details-list__truncate">{sessionId}</code>
                  </dd>
                </div>
              ) : null}
              <div className="details-list__row">
                <dt>State</dt>
                <dd>
                  <span className={`status-badge status-badge--${stateBadgeVariant(context.state)}`}>
                    {context.state}
                  </span>
                </dd>
              </div>
              {context.flow ? (
                <div className="details-list__row">
                  <dt>Grant</dt>
                  <dd>
                    <code>{context.flow}</code>
                  </dd>
                </div>
              ) : null}
            </dl>

            <div className="toolbar toolbar--compact">
              {canContinueIssuance(context) ? (
                <button type="button" disabled={busy} onClick={() => void handleContinue()}>
                  {continueActionLabel(context)}
                </button>
              ) : null}
              <button
                type="button"
                className="button--secondary"
                disabled={busy || resolving}
                onClick={handleReset}
              >
                Cancel
              </button>
            </div>
          </section>
        ) : null}

        {loadingConsent ? (
          <section className="card present-unlock">
            <h2 className="card__title">Loading storage consent</h2>
            <p className="present-unlock__lead">
              You are about to store <strong>{issuanceSummary}</strong> in your wallet. Review the
              details below, then approve with your passkey.
            </p>
          </section>
        ) : null}

        {consentView ? (
          <div className="present-section present-section--consent">
            <IssuanceConsentScreen
              view={consentView}
              onApprove={() => runAction(() => handleConsent(true))}
              onReject={() => runAction(() => handleConsent(false))}
              busy={busy}
            />
          </div>
        ) : null}

        {outcomeSuccess ? (
          <section className="card present-outcome present-outcome--success" role="status">
            <h2 className="card__title">Credential stored</h2>
            <p>
              Issuance completed for <strong>{issuanceSummary}</strong>. Open{" "}
              <Link to="/wallet">Wallet</Link> and use <strong>Sync from server</strong> to see the
              new credential, then try selective disclosure on <Link to="/present">Present</Link>.
            </p>
            <div className="toolbar toolbar--compact">
              <Link to="/wallet" className="button button--secondary">
                Open wallet
              </Link>
              <Link to="/present" className="button button--secondary">
                Present credentials
              </Link>
              <button type="button" className="button--secondary" onClick={handleReset}>
                Issue again
              </button>
            </div>
          </section>
        ) : null}

        {outcomeFailed && context?.error ? (
          <section className="card present-outcome present-outcome--error" role="alert">
            <h2 className="card__title">Issuance did not complete</h2>
            <p>
              <code>{context.error.code}</code>: {context.error.message}
            </p>
            <button type="button" className="button--secondary" onClick={handleReset}>
              Start over
            </button>
          </section>
        ) : null}

        {flowState && !showBuilder ? (
          <details className="present-dev-details">
            <summary>Developer details</summary>
            <div className="present-dev-details__body">
              {showCmdStep ? (
                <ProtocolExchangePanel
                  title="CMD & OAuth (lab)"
                  summary="The simulate button skips the real CMD redirect and exchanges a simulated authorization code. WIA is attached to the OAuth request."
                  items={[
                    { label: "CMD portal (production)", value: "https://cmd.autenticacao.gov.pt/" },
                    {
                      label: "Issuer authorization URL",
                      value:
                        context?.preparedAuthorization?.authorizationCodeUrl ??
                        "(available after Prepare authorization)",
                    },
                    {
                      label: "WIA attached to OAuth",
                      value: context?.preparedAuthorization?.wiaAttached ? "yes" : "no",
                    },
                  ]}
                />
              ) : null}

              {context?.wia?.attestation || provisioningWiaJwt ? (
                <ProtocolExchangePanel
                  title="WIA (Wallet Instance Attestation)"
                  summary="Attests the wallet instance and binds its device (DPoP) key. Sent to the authorization server during OAuth; the access token is bound to this cnf key (ISSU_21)."
                  items={[
                    { label: "Source", value: context?.wia?.attestation ? "issuance session" : "wallet provisioning" },
                    { label: "WIA state", value: context?.wia?.state ?? "(provisioning)" },
                    { label: "cnf.jkt (device key)", value: context?.wia?.attestation?.cnfJkt },
                    {
                      label: "walletInstanceId",
                      value: context?.wia?.attestation?.walletInstanceId,
                    },
                    {
                      label: "WIA bound to access token",
                      value: context?.authorizedContext?.wiaCnfJkt ?? "(after CMD step)",
                    },
                  ]}
                  payload={
                    context?.wia?.attestation ??
                    (provisioningWiaJwt
                      ? {
                          jwt: provisioningWiaJwt,
                          decoded: decodeJwtParts(provisioningWiaJwt),
                        }
                      : null)
                  }
                  payloadTitle="WIA attestation"
                />
              ) : null}

              {issuanceKa || provisioningKaJwt ? (
                <ProtocolExchangePanel
                  title="KA (Key Attestation)"
                  summary={
                    issuanceKa
                      ? "Attests the holder HSM key the credential binds to (distinct from the WIA device key)."
                      : "Issued at wallet provisioning for the holder HSM key. A session-specific KA is attached when the credential is requested."
                  }
                  items={[
                    { label: "Source", value: issuanceKa ? "issuance session" : "wallet provisioning" },
                    { label: "KA state", value: context?.ka?.state ?? "(provisioning)" },
                    { label: "keyId", value: issuanceKa?.keyId },
                    { label: "attestedJkt (holder HSM key)", value: issuanceKa?.attestedJkt },
                  ]}
                  payload={
                    issuanceKa ??
                    (provisioningKaJwt
                      ? {
                          jwt: provisioningKaJwt,
                          decoded: decodeJwtParts(provisioningKaJwt),
                        }
                      : null)
                  }
                  payloadTitle="Key attestation"
                />
              ) : null}

              <dl className="details-list">
                {sessionId ? (
                  <div className="details-list__row">
                    <dt>Session</dt>
                    <dd>
                      <code className="details-list__truncate">{sessionId}</code>
                    </dd>
                  </div>
                ) : null}
                <div className="details-list__row">
                  <dt>State</dt>
                  <dd>
                    <span
                      className={`status-badge status-badge--${stateBadgeVariant(flowState)}`}
                    >
                      {flowState}
                    </span>
                  </dd>
                </div>
                {context?.credentialIssuerId ? (
                  <div className="details-list__row">
                    <dt>Issuer</dt>
                    <dd>
                      <code className="details-list__truncate">{context.credentialIssuerId}</code>
                    </dd>
                  </div>
                ) : null}
              </dl>
              {sessionId ? (
                <button
                  type="button"
                  className="button--secondary"
                  disabled={busy}
                  onClick={() => runAction(() => refreshDebug(sessionId))}
                >
                  Refresh session debug (passkey)
                </button>
              ) : null}
              {context ? <JsonPanel title="IssuanceContext" data={context} /> : null}
              {events ? <JsonPanel title="Issuance events" data={events} defaultOpen /> : null}
            </div>
          </details>
        ) : null}
      </section>
    </AuthGate>
  );
}
