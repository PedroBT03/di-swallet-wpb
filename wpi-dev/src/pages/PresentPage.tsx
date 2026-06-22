import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { fetchWpbOperationalInfo, parseOpenId4VpDemoMode } from "../api/ops";
import {
  fetchConsentView,
  fetchPresentationEvents,
  fetchPresentationSession,
  startPresentation,
  submitConsent,
} from "../api/openid4vp";
import { AuthGate } from "../components/AuthGate";
import { AuthenticatingBanner } from "../components/AuthenticatingBanner";
import { ConsentScreen } from "../components/present/ConsentScreen";
import { PresentationSharedPanel } from "../components/present/PresentationSharedPanel";
import { PresentationStepper } from "../components/present/PresentationStepper";
import { JsonPanel } from "../components/JsonPanel";
import {
  allGroupsSelected,
  defaultCredentialSelection,
  stateBadgeVariant,
} from "../features/present/state";
import {
  PRESENT_DOCUMENTS,
  buildCustomVerifierRequestUri,
  claimOptionsForDocument,
  defaultClaimsForDocument,
  formatClaimsLabel,
  groupClaimOptions,
  type PresentDocumentType,
} from "../features/present/pidClaims";
import { useAuthedApi } from "../hooks/useAuthedApi";
import { scenariosForDocument, VP_PRESENT_SCENARIOS, type VpDemoScenario } from "../scenarios/vpDemo";
import type {
  PresentationConsentView,
  PresentationContext,
  SessionEvent,
} from "../types/openid4vp";
import { formatApiError } from "../utils/apiError";
import { formatFlowError } from "../utils/flowError";

const CLAIM_GROUPS = groupClaimOptions;

export function PresentPage() {
  const { session, withApiAuth, withSoleControl, busy, clearError } = useAuthedApi();
  const holderId = session?.holderId.trim() ?? "";

  const [selectedDocument, setSelectedDocument] = useState<PresentDocumentType>("pid");
  const claimOptions = claimOptionsForDocument(selectedDocument);
  const claimGroups = CLAIM_GROUPS(claimOptions);
  const demoScenarios = scenariosForDocument(selectedDocument);

  const [selectedClaims, setSelectedClaims] = useState<string[]>(() =>
    defaultClaimsForDocument("pid"),
  );
  const [activeScenarioId, setActiveScenarioId] = useState<string | null>(null);
  const [activeSharesLabel, setActiveSharesLabel] = useState<string | null>(null);
  const [context, setContext] = useState<PresentationContext | null>(null);
  const [consentView, setConsentView] = useState<PresentationConsentView | null>(null);
  const [events, setEvents] = useState<SessionEvent[] | null>(null);
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const [selectionError, setSelectionError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [starting, setStarting] = useState(false);
  const [loadingConsent, setLoadingConsent] = useState(false);
  const [vpDemoMode, setVpDemoMode] = useState<boolean | null>(null);

  const sessionId = context?.sessionMeta.sessionId ?? null;
  const flowState = context?.state ?? consentView?.state ?? null;
  const showBuilder = context == null && consentView == null;
  const sharesSummary = activeSharesLabel ?? (
    activeScenarioId
      ? VP_PRESENT_SCENARIOS.find((s) => s.id === activeScenarioId)?.sharesLabel
      : null
  ) ?? null;

  const refreshVpDemoMode = useCallback(() => {
    fetchWpbOperationalInfo()
      .then((info) => setVpDemoMode(parseOpenId4VpDemoMode(info)))
      .catch(() => setVpDemoMode(null));
  }, []);

  useEffect(() => {
    refreshVpDemoMode();
    const onFocus = () => refreshVpDemoMode();
    window.addEventListener("focus", onFocus);
    const intervalId = window.setInterval(refreshVpDemoMode, 15_000);
    return () => {
      window.removeEventListener("focus", onFocus);
      window.clearInterval(intervalId);
    };
  }, [refreshVpDemoMode]);

  const runAction = useCallback(
    async (action: () => Promise<void>) => {
      clearError();
      setError(null);
      setSelectionError(null);
      try {
        await action();
      } catch (err) {
        setError(formatApiError(err));
      }
    },
    [clearError],
  );

  useEffect(() => {
    if (flowState !== "CONSENT_PENDING" || !sessionId) {
      return;
    }
    let cancelled = false;
    setLoadingConsent(true);
    clearError();
    setError(null);
    void (async () => {
      try {
        const view = await withApiAuth((headers) => fetchConsentView(sessionId, holderId, headers));
        if (!cancelled) {
          setConsentView(view);
          setSelectedIds(defaultCredentialSelection(view.choiceGroups));
        }
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
  }, [flowState, sessionId, holderId, withApiAuth, clearError]);

  const refreshDebug = useCallback(
    async (id: string) => {
      const [nextContext, nextEvents] = await withApiAuth(async (headers) => {
        const ctx = await fetchPresentationSession(id, headers);
        const ev = await fetchPresentationEvents(id, headers);
        return [ctx, ev] as const;
      });
      setContext(nextContext);
      setEvents(nextEvents);
    },
    [withApiAuth],
  );

  async function handleStart(uri: string, scenarioId: string | null, sharesLabel: string | null) {
    const trimmed = uri.trim();
    if (!trimmed) {
      setError("No verifier request configured.");
      return;
    }
    if (!holderId) {
      setError("Holder id is missing. Sign out and log in again.");
      return;
    }

    refreshVpDemoMode();
    setStarting(true);
    setActiveScenarioId(scenarioId);
    setActiveSharesLabel(sharesLabel);
    setConsentView(null);
    setEvents(null);
    setContext(null);
    setSelectedIds([]);
    setError(null);

    await runAction(async () => {
      const next = await startPresentation({
        requestUri: trimmed,
        holderId,
      });
      setContext(next);
    });

    setStarting(false);
  }

  function selectDocument(documentType: PresentDocumentType) {
    if (documentType === selectedDocument) {
      return;
    }
    setError(null);
    setSelectedDocument(documentType);
    setSelectedClaims(defaultClaimsForDocument(documentType));
    setActiveScenarioId(null);
    setActiveSharesLabel(null);
  }

  function handleStartCustom() {
    if (selectedClaims.length === 0) {
      setError("Select at least one field to share.");
      return;
    }
    void handleStart(
      buildCustomVerifierRequestUri(selectedClaims, selectedDocument),
      null,
      formatClaimsLabel(selectedClaims, selectedDocument),
    );
  }

  function handleStartScenario(scenario: VpDemoScenario) {
    setSelectedDocument(scenario.documentType);
    setSelectedClaims(scenario.requestedClaims);
    void handleStart(scenario.requestUri, scenario.id, scenario.sharesLabel);
  }

  function handleReset() {
    setActiveScenarioId(null);
    setActiveSharesLabel(null);
    setContext(null);
    setConsentView(null);
    setEvents(null);
    setSelectedIds([]);
    setError(null);
    setSelectionError(null);
  }

  function toggleClaim(claimId: string) {
    setError(null);
    setSelectedClaims((prev) =>
      prev.includes(claimId) ? prev.filter((id) => id !== claimId) : [...prev, claimId],
    );
  }

  async function handleConsent(granted: boolean) {
    if (!sessionId || !consentView) {
      return;
    }

    if (granted && !allGroupsSelected(consentView.choiceGroups, selectedIds)) {
      setSelectionError("Select a credential for each requested query.");
      return;
    }

    await runAction(async () => {
      const next = await withSoleControl((headers) =>
        submitConsent(
          {
            sessionId,
            holderId,
            granted,
            selectedCredentialIds: granted ? selectedIds : [],
            reason: granted ? undefined : "Holder rejected presentation",
          },
          headers,
        ),
      );
      setContext(next);
      setConsentView(null);
      setEvents(null);
    });
  }

  function toggleCandidate(candidateId: string, queryId: string) {
    if (!consentView) {
      return;
    }
    const group = consentView.choiceGroups.find((g) => g.queryId === queryId);
    if (!group) {
      return;
    }

    setSelectionError(null);
    if (group.requiresUserSelection) {
      const withoutGroup = selectedIds.filter(
        (id) => !group.candidates.some((c) => c.candidateId === id),
      );
      setSelectedIds([...withoutGroup, candidateId]);
      return;
    }

    setSelectedIds((prev) =>
      prev.includes(candidateId)
        ? prev.filter((id) => id !== candidateId)
        : [...prev, candidateId],
    );
  }

  const outcomeSuccess = flowState === "DISPATCHED" && !context?.error;
  const outcomeFailed =
    flowState === "FAILED" ||
    flowState === "REJECTED" ||
    flowState === "EXPIRED" ||
    (flowState === "DISPATCHED" && Boolean(context?.error));

  return (
    <AuthGate>
      <section className="page page--present">
        <header className="present-hero">
          <div className="present-hero__main">
            <h1>Present credentials</h1>
            <p>
              Choose which PID or mDL fields a verifier may receive, then approve with your passkey.
              Only selected attributes leave the wallet (selective disclosure with SD-JWT or mdoc).
            </p>
          </div>
        </header>

        <AuthenticatingBanner />

        <div className="alert alert--info">
          <strong>Local demo setup</strong>
          <p>
            Start the <code>verifier-emulator</code> on port 8081 and issue a demo PID or mDL on{" "}
            <Link to="/wallet">Wallet</Link> before presenting. WPB needs{" "}
            <code>wpb.openid4vp.demo-mode=true</code> (on by default in the <code>dev</code>{" "}
            profile).
          </p>
        </div>

        {vpDemoMode === true ? (
          <div className="alert alert--info" role="status">
            WPB reports <code>openid4vp.demo-mode=true</code>. Local emulator flows are enabled.
          </div>
        ) : null}

        {vpDemoMode === false ? (
          <div className="alert alert--error" role="alert">
            <strong>WPB OpenID4VP demo-mode is off</strong>
            <p>
              The running backend reports <code>operational.demoMode.openid4vp=false</code>. Local
              emulator requests fail with <code>MissingClientId</code>. Restart WPB after pulling
              latest changes, or run:{" "}
              <code>./gradlew :app:bootRun --args=&apos;--wpb.openid4vp.demo-mode=true&apos;</code>
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

        <PresentationStepper state={flowState} />

        {showBuilder ? (
          <div className="present-builder-layout">
            <section className="card present-builder">
              <h2 className="card__title">What do you want to share?</h2>
              <p className="hint present-builder__lead">
                Choose <strong>PID</strong> (SD-JWT) or <strong>driving licence (mDL)</strong>, then
                select the fields this verifier may receive. Unselected fields stay in your wallet.
              </p>

              <div className="present-claim-group">
                <h3 className="present-claim-group__title">Document</h3>
                <div className="present-claim-toggles" role="group" aria-label="Document type">
                  {PRESENT_DOCUMENTS.map((document) => {
                    const selected = selectedDocument === document.id;
                    return (
                      <button
                        key={document.id}
                        type="button"
                        className={[
                          "present-claim-toggle",
                          selected ? "present-claim-toggle--selected" : "",
                        ]
                          .filter(Boolean)
                          .join(" ")}
                        aria-pressed={selected}
                        disabled={busy || starting || vpDemoMode === false}
                        onClick={() => selectDocument(document.id)}
                      >
                        <span className="present-claim-toggle__label">{document.label}</span>
                        <span className="present-claim-toggle__path">{document.description}</span>
                      </button>
                    );
                  })}
                </div>
              </div>

              {Object.entries(claimGroups).map(([groupName, options]) => {
                if (options.length === 0) {
                  return null;
                }
                return (
                  <div key={groupName} className="present-claim-group">
                    <h3 className="present-claim-group__title">{groupName}</h3>
                    <div className="present-claim-toggles" role="group" aria-label={groupName}>
                      {options.map((option) => {
                        const selected = selectedClaims.includes(option.id);
                        return (
                          <button
                            key={option.id}
                            type="button"
                            className={[
                              "present-claim-toggle",
                              selected ? "present-claim-toggle--selected" : "",
                            ]
                              .filter(Boolean)
                              .join(" ")}
                            aria-pressed={selected}
                            disabled={busy || starting || vpDemoMode === false}
                            onClick={() => toggleClaim(option.id)}
                          >
                            <span className="present-claim-toggle__label">{option.label}</span>
                            <code className="present-claim-toggle__path">{option.id}</code>
                          </button>
                        );
                      })}
                    </div>
                  </div>
                );
              })}

              <div className="present-builder__summary">
                <span className="hint">Verifier will request:</span>
                <strong>
                  {selectedClaims.length > 0
                    ? formatClaimsLabel(selectedClaims, selectedDocument)
                    : "No fields selected"}
                </strong>
              </div>

              <button
                type="button"
                disabled={
                  busy || starting || vpDemoMode === false || selectedClaims.length === 0
                }
                onClick={handleStartCustom}
              >
                {starting && activeScenarioId == null ? "Starting…" : "Start presentation"}
              </button>
            </section>

            <aside className="present-builder-aside">
              <section className="card present-demos">
                <h2 className="card__title">Quick demos</h2>
                <p className="hint present-demos__lead">
                  Pre-configured verifier requests for common selective-disclosure examples.
                </p>
                <ul className="present-demos-list">
                  {demoScenarios.map((scenario) => (
                    <li key={scenario.id} className="present-demos-list__item">
                      <h3 className="present-demos-list__title">{scenario.title}</h3>
                      <p className="hint">{scenario.sharesLabel}</p>
                      <button
                        type="button"
                        className="button--secondary present-demos-list__btn"
                        disabled={busy || starting || vpDemoMode === false}
                        onClick={() => handleStartScenario(scenario)}
                      >
                        {starting && activeScenarioId === scenario.id ? "Starting…" : "Try demo"}
                      </button>
                    </li>
                  ))}
                </ul>
              </section>
            </aside>
          </div>
        ) : null}

        {loadingConsent ? (
          <section className="card present-unlock">
            <h2 className="card__title">Loading consent screen</h2>
            <p className="present-unlock__lead">
              {sharesSummary ? (
                <>
                  The verifier asked for <strong>{sharesSummary}</strong>. Review the request below,
                  then approve with your passkey.
                </>
              ) : (
                "Review what the verifier requested, then approve with your passkey."
              )}
            </p>
          </section>
        ) : null}

        {consentView ? (
          <div className="present-section present-section--consent">
            <ConsentScreen
              view={consentView}
              selectedIds={selectedIds}
              onToggleCandidate={toggleCandidate}
              onApprove={() => runAction(() => handleConsent(true))}
              onReject={() => runAction(() => handleConsent(false))}
              busy={busy}
              selectionError={selectionError}
            />
          </div>
        ) : null}

        {outcomeSuccess ? (
          <section className="card present-outcome present-outcome--success" role="status">
            <h2 className="card__title">Presentation sent</h2>
            <p>
              Selective disclosure applied. The verifier received only the attributes you
              approved
              {sharesSummary ? (
                <>
                  {" "}
                  (<strong>{sharesSummary}</strong>)
                </>
              ) : null}
              .
            </p>
            <div className="toolbar toolbar--compact">
              <Link to="/log" className="button button--secondary">
                View transaction log
              </Link>
              <button type="button" className="button--secondary" onClick={handleReset}>
                Present again
              </button>
            </div>
            <PresentationSharedPanel vpToken={context?.vpToken} />
          </section>
        ) : null}

        {outcomeFailed && context?.error ? (
          <section className="card present-outcome present-outcome--error" role="alert">
            <h2 className="card__title">Presentation did not complete</h2>
            <p>{formatFlowError(context.error.code, context.error.message)}</p>
            <button type="button" className="button--secondary" onClick={handleReset}>
              Start over
            </button>
          </section>
        ) : null}

        {flowState && !showBuilder ? (
          <details className="present-dev-details">
            <summary>Developer details</summary>
            <div className="present-dev-details__body">
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
                      className={`status-badge status-badge--${stateBadgeVariant(flowState, context?.error)}`}
                    >
                      {flowState}
                    </span>
                  </dd>
                </div>
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
              {context ? <JsonPanel title="PresentationContext" data={context} /> : null}
              {events ? <JsonPanel title="Session events" data={events} defaultOpen /> : null}
            </div>
          </details>
        ) : null}
      </section>
    </AuthGate>
  );
}
