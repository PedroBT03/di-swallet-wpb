import { useCallback, useState } from "react";
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
  const [error, setError] = useState<string | null>(null);
  const [lastRaw, setLastRaw] = useState<unknown>(null);

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

  async function requestDeletion(presentationTransactionId: string) {
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

        {tab === "deletion" ? (
          <>
            <div className="card">
              <h2 className="card__title">Eligible presentations</h2>
              <p className="hint">
                Completed presentations that can be used to request erasure from the relying party.
                Request deletion returns mailto/web actions. It does not remove PIDs from your
                wallet. If the log has no RP contacts, WPB looks up current contacts from the RP
                registry automatically.
              </p>
              <div className="toolbar">
                <button
                  type="button"
                  disabled={busy || !holderId}
                  onClick={() => void runAction(loadDeletions)}
                >
                  Load eligible
                </button>
              </div>
              {eligibleDeletions ? (
                eligibleDeletions.length === 0 ? (
                  <p className="hint">
                    No eligible presentations. You need a completed <strong>Presentation</strong>{" "}
                    entry in the log (not only CredentialIssuance). Run{" "}
                    <Link to="/present">Present</Link> and approve, then reload. Existing demo
                    presentations without RP metadata should appear after restarting WPB with the
                    latest build.
                  </p>
                ) : (
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
                )
              ) : null}
            </div>

            {deletionResult ? (
              <div className="card">
                <h2 className="card__title">Deletion request result</h2>
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
              </div>
            ) : null}
          </>
        ) : (
          <>
            <div className="card">
              <h2 className="card__title">Eligible DPA reports</h2>
              <p className="hint">
                Presentation transactions that can be reported to a supervisory authority. If no DPA
                contact was stored with the presentation, WPB looks up current contacts from the RP
                registry automatically.
              </p>
              <div className="toolbar">
                <button
                  type="button"
                  disabled={busy || !holderId}
                  onClick={() => void runAction(loadDpa)}
                >
                  Load eligible
                </button>
              </div>
              {eligibleDpa ? (
                eligibleDpa.length === 0 ? (
                  <p className="hint">
                    No eligible presentations. You need at least one <strong>Presentation</strong>{" "}
                    transaction in the log. Run <Link to="/present">Present</Link> first, then
                    reload.
                  </p>
                ) : (
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
                          onClick={() => void runAction(() => reportDpa(item.presentationTransactionId))}
                        >
                          Initiate report
                        </button>
                      </li>
                    ))}
                  </ul>
                )
              ) : null}
            </div>

            {dpaResult ? (
              <div className="card">
                <h2 className="card__title">DPA report result</h2>
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
                <JsonPanel title="Substantiation (TS10)" data={dpaResult.substantiationDocument} />
              </div>
            ) : null}
          </>
        )}

        {lastRaw ? <JsonPanel title="Last API response" data={lastRaw} /> : null}
      </section>
    </AuthGate>
  );
}
