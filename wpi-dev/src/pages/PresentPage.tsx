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
import { PresentationStepper } from "../components/present/PresentationStepper";
import { JsonPanel } from "../components/JsonPanel";
import {
  allGroupsSelected,
  defaultCredentialSelection,
  stateBadgeVariant,
} from "../features/present/state";
import { useAuthedApi } from "../hooks/useAuthedApi";
import { VP_DEMO_SCENARIOS } from "../scenarios/vpDemo";
import type {
  PresentationConsentView,
  PresentationContext,
  SessionEvent,
} from "../types/openid4vp";
import { formatApiError } from "../utils/apiError";

export function PresentPage() {
  const { session, withProtectedAction, busy, clearError } = useAuthedApi();
  const holderId = session?.holderId.trim() ?? "";

  const [requestUri, setRequestUri] = useState(VP_DEMO_SCENARIOS[0]?.requestUri ?? "");
  const [context, setContext] = useState<PresentationContext | null>(null);
  const [consentView, setConsentView] = useState<PresentationConsentView | null>(null);
  const [events, setEvents] = useState<SessionEvent[] | null>(null);
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const [selectionError, setSelectionError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [starting, setStarting] = useState(false);
  const [vpDemoMode, setVpDemoMode] = useState<boolean | null>(null);

  const sessionId = context?.sessionMeta.sessionId ?? null;
  const flowState = context?.state ?? consentView?.state ?? null;
  const awaitingConsentUnlock =
    flowState === "CONSENT_PENDING" && !consentView && sessionId != null;

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

  const loadConsentView = useCallback(
    async (id: string) => {
      const view = await withProtectedAction((headers) =>
        fetchConsentView(id, holderId, headers),
      );
      setConsentView(view);
      setSelectedIds(defaultCredentialSelection(view.choiceGroups));
    },
    [holderId, withProtectedAction],
  );

  const refreshDebug = useCallback(
    async (id: string) => {
      const [nextContext, nextEvents] = await withProtectedAction(async (headers) => {
        const ctx = await fetchPresentationSession(id, headers);
        const ev = await fetchPresentationEvents(id, headers);
        return [ctx, ev] as const;
      });
      setContext(nextContext);
      setEvents(nextEvents);
    },
    [withProtectedAction],
  );

  async function handleStart() {
    const trimmed = requestUri.trim();
    if (!trimmed) {
      setError("Request URI is required.");
      return;
    }
    if (!holderId) {
      setError("Holder id is missing. Sign out and log in again.");
      return;
    }

    refreshVpDemoMode();
    setStarting(true);
    setConsentView(null);
    setEvents(null);
    setContext(null);
    setSelectedIds([]);

    await runAction(async () => {
      const next = await startPresentation({
        requestUri: trimmed,
        holderId,
      });
      setContext(next);
    });

    setStarting(false);
  }

  async function handleLoadConsent() {
    if (!sessionId || busy) {
      return;
    }
    await runAction(() => loadConsentView(sessionId));
  }

  function applyScenario(uri: string) {
    setRequestUri(uri);
    setError(null);
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

  async function handleConsent(granted: boolean) {
    if (!sessionId || !consentView) {
      return;
    }

    if (granted && !allGroupsSelected(consentView.choiceGroups, selectedIds)) {
      setSelectionError("Select a credential for each requested query.");
      return;
    }

    await runAction(async () => {
      const next = await withProtectedAction((headers) =>
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

  return (
    <AuthGate>
      <section className="page">
        <header className="page__header">
          <h1>Present</h1>
          <p className="page__lead">
            Start an OpenID4VP presentation from a verifier <code>request_uri</code>, review
            the consent screen, then approve or reject with your passkey.
          </p>
        </header>

        <AuthenticatingBanner />

        <div className="alert alert--info">
          <strong>Local demo setup</strong>
          <p>
            Start the <code>verifier-emulator</code> on port 8081 (serves{" "}
            <code>verifier_info.x5c</code> for PKIX trust) and issue a demo PID on{" "}
            <Link to="/wallet">Wallet</Link> before PID scenarios. WPB needs{" "}
            <code>wpb.openid4vp.demo-mode=true</code> (on by default in the <code>dev</code> profile).
          </p>
        </div>

        {vpDemoMode === true ? (
          <div className="alert alert--info" role="status">
            WPB reports <code>openid4vp.demo-mode=true</code> — local emulator flows are enabled.
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
              Check <Link to="/health">Backend health</Link> — the dev proxy targets{" "}
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

        <div className="present-grid">
          <section className="card present-section">
            <h2 className="card__title">Request URI</h2>
            <div className="form">
              <label className="form__field">
                <span className="form__label">request_uri</span>
                <textarea
                  className="form__textarea"
                  value={requestUri}
                  onChange={(e) => setRequestUri(e.target.value)}
                  rows={3}
                  disabled={busy || starting}
                  spellCheck={false}
                />
              </label>
              <div className="toolbar toolbar--compact">
                <button type="button" disabled={busy || starting} onClick={handleStart}>
                  {starting ? "Starting…" : "Start presentation"}
                </button>
              </div>
            </div>
          </section>

          <section className="card present-section">
            <h2 className="card__title">Demo scenarios</h2>
            <p className="hint present-scenarios__lead">
              Pre-filled URIs served by the verifier emulator.
            </p>
            <ul className="present-scenario-list">
              {VP_DEMO_SCENARIOS.map((scenario) => (
                <li key={scenario.id} className="present-scenario-list__item">
                  <div className="present-scenario-list__head">
                    <strong>{scenario.title}</strong>
                    {scenario.requiresPid ? (
                      <span className="status-badge status-badge--unknown">needs PID</span>
                    ) : null}
                  </div>
                  <p className="hint">{scenario.description}</p>
                  <button
                    type="button"
                    className="button--secondary"
                    disabled={busy || starting}
                    onClick={() => applyScenario(scenario.requestUri)}
                  >
                    Use URI
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
                      Unlock &amp; load consent (passkey)
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

          {context ? (
            <div className="present-section present-section--debug">
              <JsonPanel title="PresentationContext" data={context} />
            </div>
          ) : null}

          {events ? (
            <div className="present-section present-section--debug">
              <JsonPanel title="Session events" data={events} defaultOpen />
            </div>
          ) : null}
        </div>
      </section>
    </AuthGate>
  );
}
