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
import { IssuanceConsentScreen } from "../components/issue/IssuanceConsentScreen";
import { IssuanceStepper } from "../components/issue/IssuanceStepper";
import { JsonPanel } from "../components/JsonPanel";
import {
  canContinueIssuance,
  continueActionLabel,
  flowKind,
  isIssuanceSuccess,
  isTerminalIssuanceState,
  primaryCredentialConfigurationId,
  stateBadgeVariant,
} from "../features/issue/state";
import { useAuthedApi } from "../hooks/useAuthedApi";
import { VCI_DEMO_SCENARIOS } from "../scenarios/vciDemo";
import type { IssuanceConsentView, IssuanceContext, IssuanceEvent } from "../types/openid4vci";
import { formatApiError } from "../utils/apiError";

const SIMULATED_AUTH_CODE = "code-abc";

export function IssuePage() {
  const { session, withProtectedAction, busy, clearError } = useAuthedApi();
  const holderId = session?.holderId.trim() ?? "";

  const [offerUri, setOfferUri] = useState(VCI_DEMO_SCENARIOS[0]?.offerUri ?? "");
  const [txCode, setTxCode] = useState(VCI_DEMO_SCENARIOS[0]?.defaultTxCode ?? "1234");
  const [context, setContext] = useState<IssuanceContext | null>(null);
  const [consentView, setConsentView] = useState<IssuanceConsentView | null>(null);
  const [events, setEvents] = useState<IssuanceEvent[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [resolving, setResolving] = useState(false);
  const [vciDemoMode, setVciDemoMode] = useState<boolean | null>(null);

  const sessionId = context?.sessionMeta.sessionId ?? null;
  const flowState = context?.state ?? consentView?.state ?? null;
  const awaitingConsentUnlock =
    flowState === "ISSUANCE_CONSENT_PENDING" && !consentView && sessionId != null;
  const showSuccessLink = flowState != null && isIssuanceSuccess(flowState);

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
      const view = await withProtectedAction((headers) =>
        fetchIssuanceConsentView(id, holderId, headers),
      );
      setConsentView(view);
    },
    [holderId, withProtectedAction],
  );

  const refreshDebug = useCallback(
    async (id: string) => {
      const [nextContext, nextEvents] = await withProtectedAction(async (headers) => {
        const ctx = await fetchIssuanceSession(id, headers);
        const ev = await fetchIssuanceEvents(id, headers);
        return [ctx, ev] as const;
      });
      setContext(nextContext);
      setEvents(nextEvents);
    },
    [withProtectedAction],
  );

  async function advanceIssuance(ctx: IssuanceContext): Promise<IssuanceContext> {
    const id = ctx.sessionMeta.sessionId;
    switch (ctx.state) {
      case "OFFER_RESOLVED":
        if (flowKind(ctx) === "PRE_AUTHORIZED_CODE") {
          return completePreAuthorized(id, txCode.trim() || undefined);
        }
        return prepareAuthorization(id);
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

  async function handleResolve() {
    const trimmed = offerUri.trim();
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

    await runAction(async () => {
      const next = await resolveOffer({ offerUri: trimmed, holderId });
      setContext(next);
      if (isTerminalIssuanceState(next.state) && next.error) {
        setError(`${next.error.code}: ${next.error.message}`);
      }
    });

    setResolving(false);
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

  async function handleLoadConsent() {
    if (!sessionId || busy) {
      return;
    }
    await runAction(() => loadConsentView(sessionId));
  }

  function applyScenario(scenario: (typeof VCI_DEMO_SCENARIOS)[number]) {
    setOfferUri(scenario.offerUri);
    if (scenario.defaultTxCode) {
      setTxCode(scenario.defaultTxCode);
    }
    setError(null);
  }

  async function handleConsent(granted: boolean) {
    if (!sessionId) {
      return;
    }

    await runAction(async () => {
      const next = await withProtectedAction((headers) =>
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

  const showTxCodeField =
    context == null ||
    flowKind(context) === "PRE_AUTHORIZED_CODE" ||
    context.resolvedOffer?.preAuthorizedGrant?.txCodeRequired === true;

  return (
    <AuthGate>
      <section className="page">
        <header className="page__header">
          <h1>Issue</h1>
          <p className="page__lead">
            Resolve an OpenID4VCI credential offer, complete issuer authorization, request the
            credential, then approve storage with your passkey.
          </p>
        </header>

        <AuthenticatingBanner />

        <div className="alert alert--info">
          <strong>Local demo setup</strong>
          <p>
            WPB uses the <strong>simulated issuer</strong> when{" "}
            <code>wpb.openid4vci.demo-mode=true</code> (default in <code>dev</code>). Initialize
            your wallet on <Link to="/wallet">Wallet</Link> before device-bound credentials.
          </p>
        </div>

        {vciDemoMode === true ? (
          <div className="alert alert--info" role="status">
            WPB reports <code>openid4vci.demo-mode=true</code> — simulated issuer flows are enabled.
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
          </div>
        ) : null}

        {error ? (
          <div className="alert alert--error" role="alert">
            {error}
          </div>
        ) : null}

        {showSuccessLink ? (
          <div className="alert alert--info" role="status">
            <strong>Credential issued</strong>
            <p>
              Open <Link to="/wallet">Wallet</Link> and use <strong>Unlock &amp; sync from server</strong>{" "}
              to see the new credential.
            </p>
          </div>
        ) : null}

        <IssuanceStepper state={flowState} />

        <div className="present-grid">
          <section className="card present-section">
            <h2 className="card__title">Credential offer</h2>
            <div className="form">
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
              {showTxCodeField ? (
                <label className="form__field">
                  <span className="form__label">tx_code (pre-authorized offers)</span>
                  <input
                    className="form__input"
                    value={txCode}
                    onChange={(e) => setTxCode(e.target.value)}
                    disabled={busy || resolving}
                    autoComplete="off"
                  />
                </label>
              ) : null}
              <div className="toolbar toolbar--compact">
                <button type="button" disabled={busy || resolving} onClick={() => void handleResolve()}>
                  {resolving ? "Resolving…" : "Resolve offer"}
                </button>
                {context && canContinueIssuance(context) ? (
                  <button
                    type="button"
                    className="button--secondary"
                    disabled={busy}
                    onClick={() => void handleContinue()}
                  >
                    {continueActionLabel(context)}
                  </button>
                ) : null}
              </div>
            </div>
          </section>

          <section className="card present-section">
            <h2 className="card__title">Demo scenarios</h2>
            <p className="hint present-scenarios__lead">
              Simulated issuer offers served by WPB (no external issuer required).
            </p>
            <ul className="present-scenario-list">
              {VCI_DEMO_SCENARIOS.map((scenario) => (
                <li key={scenario.id} className="present-scenario-list__item">
                  <div className="present-scenario-list__head">
                    <strong>{scenario.title}</strong>
                    <span className="status-badge status-badge--unknown">{scenario.grant}</span>
                  </div>
                  <p className="hint">{scenario.description}</p>
                  <button
                    type="button"
                    className="button--secondary"
                    disabled={busy || resolving}
                    onClick={() => applyScenario(scenario)}
                  >
                    Use offer
                  </button>
                </li>
              ))}
            </ul>
          </section>

          {flowState ? (
            <section className="card present-section present-section--status">
              <h2 className="card__title">Session status</h2>
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
                    <span className={`status-badge status-badge--${stateBadgeVariant(flowState)}`}>
                      {flowState}
                    </span>
                  </dd>
                </div>
                {context?.flow ? (
                  <div className="details-list__row">
                    <dt>Grant</dt>
                    <dd>
                      <code>{context.flow}</code>
                    </dd>
                  </div>
                ) : null}
                {context?.credentialIssuerId ? (
                  <div className="details-list__row">
                    <dt>Issuer</dt>
                    <dd>
                      <code className="details-list__truncate">{context.credentialIssuerId}</code>
                    </dd>
                  </div>
                ) : null}
                {context?.sessionMeta.correlationId ? (
                  <div className="details-list__row">
                    <dt>Correlation</dt>
                    <dd>
                      <code>{context.sessionMeta.correlationId}</code>
                    </dd>
                  </div>
                ) : null}
                {context?.error ? (
                  <div className="details-list__row">
                    <dt>Error</dt>
                    <dd>
                      <code>{context.error.code}</code> — {context.error.message}
                    </dd>
                  </div>
                ) : null}
              </dl>
              {sessionId ? (
                <div className="toolbar toolbar--compact">
                  {awaitingConsentUnlock ? (
                    <button type="button" disabled={busy} onClick={() => void handleLoadConsent()}>
                      Unlock &amp; load storage consent (passkey)
                    </button>
                  ) : null}
                  <button
                    type="button"
                    className="button--secondary"
                    disabled={busy}
                    onClick={() => runAction(() => refreshDebug(sessionId))}
                  >
                    Refresh debug (passkey)
                  </button>
                </div>
              ) : null}
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

          {context ? (
            <div className="present-section present-section--debug">
              <JsonPanel title="IssuanceContext" data={context} />
            </div>
          ) : null}

          {events ? (
            <div className="present-section present-section--debug">
              <JsonPanel title="Issuance events" data={events} defaultOpen />
            </div>
          ) : null}
        </div>
      </section>
    </AuthGate>
  );
}
