import { useCallback, useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import {
  fetchEligibleDeletions,
  fetchEligibleDpaReports,
  initiateDeletion,
  initiateDpaReport,
} from "../api/privacy";
import { AuthGate } from "../components/AuthGate";
import { AuthenticatingBanner } from "../components/AuthenticatingBanner";
import { ActionUriList } from "../components/privacy/ActionUriList";
import { JsonPanel } from "../components/JsonPanel";
import { formatOccurredAt } from "../features/log/state";
import { useAuthedApi } from "../hooks/useAuthedApi";
import type {
  DataDeletionInitiateResponse,
  DpaReportInitiateResponse,
  EligibleDpaReportPresentation,
  EligiblePresentation,
} from "../types/privacy";
import { formatApiError } from "../utils/apiError";

type PrivacyTab = "deletion" | "dpa";

export function PrivacyPage() {
  const { session, withApiAuth, busy, clearError } = useAuthedApi();
  const holderId = session?.holderId.trim() ?? "";

  const [tab, setTab] = useState<PrivacyTab>("deletion");
  const [eligibleDeletions, setEligibleDeletions] = useState<EligiblePresentation[] | null>(null);
  const [eligibleDpa, setEligibleDpa] = useState<EligibleDpaReportPresentation[] | null>(null);
  const [deletionResult, setDeletionResult] = useState<DataDeletionInitiateResponse | null>(null);
  const [dpaResult, setDpaResult] = useState<DpaReportInitiateResponse | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [lastRaw, setLastRaw] = useState<unknown>(null);

  const deletionContactRef = useRef<HTMLDivElement>(null);
  const dpaContactRef = useRef<HTMLDivElement>(null);
  const scrollToDeletionContact = useRef(false);
  const scrollToDpaContact = useRef(false);

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

  async function loadDeletions() {
    await withApiAuth(async (headers) => {
      const list = await fetchEligibleDeletions(holderId, headers);
      setEligibleDeletions(list);
      setDeletionResult(null);
      setLastRaw(list);
    });
  }

  async function loadDpa() {
    await withApiAuth(async (headers) => {
      const list = await fetchEligibleDpaReports(holderId, headers);
      setEligibleDpa(list);
      setDpaResult(null);
      setLastRaw(list);
    });
  }

  const loadDeletionsRef = useRef(loadDeletions);
  loadDeletionsRef.current = loadDeletions;

  const loadDpaRef = useRef(loadDpa);
  loadDpaRef.current = loadDpa;

  useEffect(() => {
    if (!holderId) {
      return;
    }
    let cancelled = false;
    void (async () => {
      clearError();
      setError(null);
      setRefreshing(true);
      try {
        if (tab === "deletion") {
          await loadDeletionsRef.current();
        } else {
          await loadDpaRef.current();
        }
      } catch (err) {
        if (!cancelled) {
          setError(formatApiError(err));
        }
      } finally {
        if (!cancelled) {
          setRefreshing(false);
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [holderId, tab, clearError]);

  async function handleRefresh() {
    setRefreshing(true);
    try {
      if (tab === "deletion") {
        await loadDeletions();
      } else {
        await loadDpa();
      }
    } catch (err) {
      setError(formatApiError(err));
    } finally {
      setRefreshing(false);
    }
  }

  async function requestDeletion(presentationTransactionId: string) {
    scrollToDeletionContact.current = true;
    await withApiAuth(async (headers) => {
      const result = await initiateDeletion(
        {
          holderId,
          presentationTransactionId,
          deleteAllPresented: true,
        },
        headers,
      );
      setDeletionResult(result);
      setLastRaw(result);
    });
  }

  async function reportDpa(presentationTransactionId: string) {
    scrollToDpaContact.current = true;
    await withApiAuth(async (headers) => {
      const result = await initiateDpaReport(
        {
          holderId,
          presentationTransactionId,
        },
        headers,
      );
      setDpaResult(result);
      setLastRaw(result);
    });
  }

  useEffect(() => {
    if (!deletionResult || !scrollToDeletionContact.current) {
      return;
    }
    scrollToDeletionContact.current = false;
    deletionContactRef.current?.scrollIntoView({ behavior: "smooth", block: "start" });
  }, [deletionResult]);

  useEffect(() => {
    if (!dpaResult || !scrollToDpaContact.current) {
      return;
    }
    scrollToDpaContact.current = false;
    dpaContactRef.current?.scrollIntoView({ behavior: "smooth", block: "start" });
  }, [dpaResult]);

  return (
    <AuthGate>
      <section className="page">
        <header className="page__header">
          <h1>Privacy</h1>
          <p className="page__lead">
            GDPR data deletion requests and DPA reports against relying parties.
          </p>
        </header>

        {busy ? <AuthenticatingBanner /> : null}
        {error ? <div className="alert alert--error">{error}</div> : null}

        <div className="tab-bar">
          <button
            type="button"
            className={`tab-bar__btn ${tab === "deletion" ? "is-active" : ""}`}
            onClick={() => setTab("deletion")}
          >
            Data deletion
          </button>
          <button
            type="button"
            className={`tab-bar__btn ${tab === "dpa" ? "is-active" : ""}`}
            onClick={() => setTab("dpa")}
          >
            DPA report
          </button>
        </div>

        <div className="page-stack">
          {tab === "deletion" ? (
            <>
              <div className="card">
                <h2 className="card__title">
                  Eligible presentations
                  {eligibleDeletions ? ` (${eligibleDeletions.length})` : ""}
                </h2>
                <p className="hint">
                  Completed presentations that can be used to request erasure from the relying party.
                  Request deletion returns mailto/web actions. It does not remove PIDs from your
                  wallet. If the log has no RP contacts, WPB looks up current contacts from the RP
                  registry automatically.
                </p>
                <div className="toolbar privacy-card__toolbar">
                  <button
                    type="button"
                    className="button button--secondary"
                    disabled={busy || refreshing || !holderId}
                    onClick={() => void runAction(handleRefresh)}
                  >
                    {refreshing ? (
                      <>
                        <span className="btn-spinner" aria-hidden="true" />
                        Refreshing…
                      </>
                    ) : (
                      "Refresh"
                    )}
                  </button>
                </div>
                {refreshing && eligibleDeletions === null ? (
                  <p className="hint">Loading eligible presentations…</p>
                ) : eligibleDeletions ? (
                  eligibleDeletions.length === 0 ? (
                    <p className="hint">
                      No eligible presentations. You need a completed <strong>Presentation</strong>{" "}
                      entry in the log (not only CredentialIssuance). Run{" "}
                      <Link to="/present">Present</Link> and approve, then refresh. Existing demo
                      presentations without RP metadata should appear after restarting WPB with the
                      latest build.
                    </p>
                  ) : (
                    <div className="privacy-list__scroll panel-scroll">
                      <ul className="privacy-list">
                        {eligibleDeletions.map((item) => (
                          <li key={item.presentationTransactionId} className="privacy-list__item">
                            <div>
                              <strong>{item.rpName ?? item.rpIdentifier ?? "Unknown RP"}</strong>
                              <div className="hint mono-sm">{item.presentationTransactionId}</div>
                              <div className="hint">{formatOccurredAt(item.presentationTime)}</div>
                              <div className="hint">
                                Claims:{" "}
                                {item.presentedClaims
                                  .flatMap((claim) => claim.claims)
                                  .join(", ") || "n/a"}
                              </div>
                            </div>
                            <button
                              type="button"
                              className="button button--sm"
                              disabled={busy}
                              onClick={() =>
                                void runAction(() => requestDeletion(item.presentationTransactionId))
                              }
                            >
                              Request deletion
                            </button>
                          </li>
                        ))}
                      </ul>
                    </div>
                  )
                ) : null}
              </div>

              <div className="card" ref={deletionContactRef} id="privacy-deletion-contact">
                <h2 className="card__title">Contact the relying party</h2>
                {deletionResult ? (
                  <>
                    {deletionResult.userNotice ? (
                      <div className="alert alert--info" role="status">
                        {deletionResult.userNotice}
                      </div>
                    ) : null}
                    <ActionUriList
                      actions={deletionResult.availableActions}
                      emptyMessage="No contact means were found for this relying party."
                    />
                    <JsonPanel title="Response" data={deletionResult} />
                  </>
                ) : (
                  <p className="hint">
                    Choose a presentation above and click <strong>Request deletion</strong> to see
                    mailto and web contact options.
                  </p>
                )}
              </div>
            </>
          ) : (
            <>
              <div className="card">
                <h2 className="card__title">
                  Eligible DPA reports
                  {eligibleDpa ? ` (${eligibleDpa.length})` : ""}
                </h2>
                <p className="hint">
                  Presentation transactions that can be reported to a supervisory authority. If no DPA
                  contact was stored with the presentation, WPB looks up current contacts from the RP
                  registry automatically.
                </p>
                <div className="toolbar privacy-card__toolbar">
                  <button
                    type="button"
                    className="button button--secondary"
                    disabled={busy || refreshing || !holderId}
                    onClick={() => void runAction(handleRefresh)}
                  >
                    {refreshing ? (
                      <>
                        <span className="btn-spinner" aria-hidden="true" />
                        Refreshing…
                      </>
                    ) : (
                      "Refresh"
                    )}
                  </button>
                </div>
                {refreshing && eligibleDpa === null ? (
                  <p className="hint">Loading eligible presentations…</p>
                ) : eligibleDpa ? (
                  eligibleDpa.length === 0 ? (
                    <p className="hint">
                      No eligible presentations. You need at least one <strong>Presentation</strong>{" "}
                      transaction in the log. Run <Link to="/present">Present</Link> first, then
                      refresh.
                    </p>
                  ) : (
                    <div className="privacy-list__scroll panel-scroll">
                      <ul className="privacy-list">
                        {eligibleDpa.map((item) => (
                          <li key={item.presentationTransactionId} className="privacy-list__item">
                            <div>
                              <strong>{item.rpName ?? item.rpIdentifier ?? "Unknown RP"}</strong>
                              <div className="hint mono-sm">{item.presentationTransactionId}</div>
                              <div className="hint">{formatOccurredAt(item.presentationTime)}</div>
                              <div className="hint">Result: {item.transactionResult}</div>
                            </div>
                            <button
                              type="button"
                              className="button button--sm"
                              disabled={busy}
                              onClick={() =>
                                void runAction(() => reportDpa(item.presentationTransactionId))
                              }
                            >
                              Initiate report
                            </button>
                          </li>
                        ))}
                      </ul>
                    </div>
                  )
                ) : null}
              </div>

              <div className="card" ref={dpaContactRef} id="privacy-dpa-contact">
                <h2 className="card__title">Contact the supervisory authority</h2>
                {dpaResult ? (
                  <>
                    <dl className="details-list">
                      {dpaResult.dpaName ? (
                        <div className="details-list__row">
                          <dt>DPA</dt>
                          <dd>
                            {dpaResult.dpaName}
                            {dpaResult.dpaCountry ? ` (${dpaResult.dpaCountry})` : ""}
                          </dd>
                        </div>
                      ) : null}
                      {dpaResult.dnsName ? (
                        <div className="details-list__row">
                          <dt>DNS name</dt>
                          <dd>
                            {dpaResult.dnsName}
                            {dpaResult.dnsNameSource ? ` (${dpaResult.dnsNameSource})` : ""}
                          </dd>
                        </div>
                      ) : null}
                    </dl>
                    {dpaResult.userNotice ? (
                      <div className="alert alert--info" role="status">
                        {dpaResult.userNotice}
                      </div>
                    ) : null}
                    <ActionUriList
                      actions={dpaResult.availableActions}
                      emptyMessage="No contact means were found for this presentation."
                    />
                    <JsonPanel title="Substantiation document" data={dpaResult.substantiationDocument} />
                  </>
                ) : (
                  <p className="hint">
                    Choose a presentation above and click <strong>Initiate report</strong> to see
                    contact options for the supervisory authority.
                  </p>
                )}
              </div>
            </>
          )}

          {lastRaw ? <JsonPanel title="Last API response" data={lastRaw} /> : null}
        </div>
      </section>
    </AuthGate>
  );
}
