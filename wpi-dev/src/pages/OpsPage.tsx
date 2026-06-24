import { useCallback, useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { fetchTrustMark } from "../api/trustMark";
import {
  fetchStatusListEntry,
  fetchStatusListJson,
  fetchStatusListJwt,
} from "../api/statusList";
import { JsonPanel } from "../components/JsonPanel";
import { DEFAULT_STATUS_LIST_ID } from "../types/statusList";
import type { StatusListEntry, StatusListJsonPayload } from "../types/statusList";
import type { TrustMarkAction, TrustMarkView } from "../types/trustMark";
import { formatApiError } from "../utils/apiError";

type OpsTab = "trust" | "status";

const TRUST_ACTION_LABELS: Record<TrustMarkAction["type"], string> = {
  CERTIFIED_WALLETS_LIST: "Certified wallets",
  WALLET_SOLUTION_INFO: "Solution information",
};

function trustActionLabel(action: TrustMarkAction): string {
  return TRUST_ACTION_LABELS[action.type] ?? action.type;
}

export function OpsPage() {
  const [tab, setTab] = useState<OpsTab>("trust");
  const [trustMark, setTrustMark] = useState<TrustMarkView | null>(null);
  const [listId, setListId] = useState(DEFAULT_STATUS_LIST_ID);
  const [indexInput, setIndexInput] = useState("0");
  const [statusJwt, setStatusJwt] = useState<string | null>(null);
  const [statusJson, setStatusJson] = useState<StatusListJsonPayload | null>(null);
  const [statusEntry, setStatusEntry] = useState<StatusListEntry | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [refreshingTrust, setRefreshingTrust] = useState(false);
  const [refreshingStatus, setRefreshingStatus] = useState(false);
  const [lookingUpIndex, setLookingUpIndex] = useState(false);

  const loadTrustMark = useCallback(async () => {
    setRefreshingTrust(true);
    setError(null);
    try {
      const view = await fetchTrustMark();
      setTrustMark(view);
    } catch (err) {
      setTrustMark(null);
      setError(formatApiError(err));
    } finally {
      setRefreshingTrust(false);
    }
  }, []);

  const loadTrustMarkRef = useRef(loadTrustMark);
  loadTrustMarkRef.current = loadTrustMark;

  const loadStatusList = useCallback(async () => {
    setRefreshingStatus(true);
    setError(null);
    setStatusEntry(null);
    try {
      const trimmedId = listId.trim();
      const [jwt, json] = await Promise.all([
        fetchStatusListJwt(trimmedId),
        fetchStatusListJson(trimmedId),
      ]);
      setStatusJwt(jwt);
      setStatusJson(json);
    } catch (err) {
      setStatusJwt(null);
      setStatusJson(null);
      setError(formatApiError(err));
    } finally {
      setRefreshingStatus(false);
    }
  }, [listId]);

  const loadStatusListRef = useRef(loadStatusList);
  loadStatusListRef.current = loadStatusList;

  useEffect(() => {
    if (tab !== "trust") {
      return;
    }
    void loadTrustMarkRef.current();
  }, [tab]);

  useEffect(() => {
    if (tab !== "status") {
      return;
    }
    void loadStatusListRef.current();
  }, [tab]);

  async function lookupStatusIndex() {
    const index = Number.parseInt(indexInput.trim(), 10);
    if (Number.isNaN(index) || index < 0) {
      setError("Revocation index must be a non-negative integer.");
      return;
    }
    setLookingUpIndex(true);
    setError(null);
    try {
      const entry = await fetchStatusListEntry(listId.trim(), index);
      setStatusEntry(entry);
    } catch (err) {
      setStatusEntry(null);
      setError(formatApiError(err));
    } finally {
      setLookingUpIndex(false);
    }
  }

  return (
    <section className="page page--ops">
      <header className="page__header">
        <h1>Operations</h1>
        <p className="page__lead">
          Trust mark and revocation lists for this wallet.
        </p>
      </header>

      {error ? <div className="alert alert--error">{error}</div> : null}

      <div className="tab-bar">
        <button
          type="button"
          className={`tab-bar__btn ${tab === "trust" ? "is-active" : ""}`}
          onClick={() => setTab("trust")}
        >
          Trust mark
        </button>
        <button
          type="button"
          className={`tab-bar__btn ${tab === "status" ? "is-active" : ""}`}
          onClick={() => setTab("status")}
        >
          Revocation lists
        </button>
      </div>

      <div className="page-stack">
        <div className="card ops-scenario-card">
          <h2 className="card__title">What to demonstrate</h2>
          {tab === "trust" ? (
            <ol className="ops-scenario-card__steps">
              <li>
                Show the <strong>wallet solution id</strong> and the public URLs WPB publishes so
                verifiers can find <strong>certification material</strong>.
              </li>
              <li>
                In this lab the resource is served locally by WPB; production uses Commission-hosted
                HTTPS endpoints.
              </li>
            </ol>
          ) : (
            <ol className="ops-scenario-card__steps">
              <li>
                On <Link to="/wallet">Wallet</Link>, revoke a credential or HSM key and note the{" "}
                <strong>revocation index</strong> shown in the success message or credential panel.
              </li>
              <li>
                Open the published <strong>status list</strong> below to see capacity and how many
                indices are allocated.
              </li>
              <li>
                Look up that index: verifiers use the same list to see whether the entry is{" "}
                <strong>ACTIVE</strong> or <strong>REVOKED</strong>.
              </li>
            </ol>
          )}
        </div>

        {tab === "trust" ? (
          <div className="card">
            <h2 className="card__title">Wallet trust mark</h2>
            <p className="hint">
              Public certification bundle for this wallet provider: which solution this is, where to
              download logo and certification text, and links to the certified-wallet registry.
            </p>
            <div className="toolbar ops-card__toolbar">
              <button
                type="button"
                className="button button--secondary"
                disabled={refreshingTrust}
                onClick={() => void loadTrustMark()}
              >
                {refreshingTrust ? (
                  <>
                    <span className="btn-spinner" aria-hidden="true" />
                    Refreshing…
                  </>
                ) : (
                  "Refresh"
                )}
              </button>
            </div>

            {refreshingTrust && !trustMark ? (
              <p className="hint">Loading trust mark…</p>
            ) : trustMark ? (
              <>
                {trustMark.userNotice ? (
                  <div className="alert alert--info" role="status">
                    {trustMark.userNotice}
                  </div>
                ) : null}

                <dl className="details-list ops-trust-details">
                  <div className="details-list__row">
                    <dt>Wallet solution</dt>
                    <dd>
                      <code>{trustMark.walletSolutionId ?? trustMark.information?.walletSolutionId ?? "Not set"}</code>
                    </dd>
                  </div>
                  {trustMark.information?.trustMarkResourceUrl ? (
                    <div className="details-list__row">
                      <dt>Certification resource</dt>
                      <dd>
                        <code className="mono-sm">{trustMark.information.trustMarkResourceUrl}</code>
                      </dd>
                    </div>
                  ) : null}
                  {trustMark.information?.listOfCertifiedWalletsUrl ? (
                    <div className="details-list__row">
                      <dt>Certified wallets</dt>
                      <dd>
                        <code className="mono-sm">{trustMark.information.listOfCertifiedWalletsUrl}</code>
                      </dd>
                    </div>
                  ) : null}
                  {trustMark.information?.walletSolutionInfoPageUrl ? (
                    <div className="details-list__row">
                      <dt>Solution information</dt>
                      <dd>
                        <code className="mono-sm">{trustMark.information.walletSolutionInfoPageUrl}</code>
                      </dd>
                    </div>
                  ) : null}
                </dl>

                {trustMark.resource?.localizedText || trustMark.resource?.imageUrl ? (
                  <section className="ops-trust-preview">
                    <h3 className="ops-trust-preview__title">Certification material</h3>
                    <div className="ops-trust-panel">
                      {trustMark.resource?.imageUrl ? (
                        <img
                          src={trustMark.resource.imageUrl}
                          alt={trustMark.resource.imageName ?? "Trust mark"}
                          className="trust-mark__image ops-trust-panel__image"
                        />
                      ) : null}
                      {trustMark.resource?.localizedText ? (
                        <p className="ops-trust-panel__text">{trustMark.resource.localizedText}</p>
                      ) : null}
                    </div>
                  </section>
                ) : (
                  <p className="hint">
                    Certification text and logo could not be loaded from the resource URL. Check that
                    WPB is running and the trust mark resource is reachable.
                  </p>
                )}

                {trustMark.actions.length > 0 ? (
                  <>
                    <h3 className="ops-trust-preview__title">Quick links</h3>
                    <ul className="action-uri-list ops-action-list">
                      {trustMark.actions.map((action) => (
                        <li key={action.uri} className="action-uri-list__item">
                          <span className="action-uri-list__channel">{trustActionLabel(action)}</span>
                          <code className="action-uri-list__uri">{action.uri}</code>
                          <div className="action-uri-list__buttons">
                            <a
                              className="button button--secondary button--sm"
                              href={action.uri}
                              target="_blank"
                              rel="noreferrer"
                            >
                              Open
                            </a>
                          </div>
                        </li>
                      ))}
                    </ul>
                  </>
                ) : null}

                <JsonPanel title="Full response" data={trustMark} />
              </>
            ) : null}
          </div>
        ) : (
          <>
            <div className="card">
              <h2 className="card__title">Published status list</h2>
              <p className="hint">
                Token Status List published by WPB. Verifiers download the JWT and check whether a
                credential or key index is revoked. Default list id is{" "}
                <code>{DEFAULT_STATUS_LIST_ID}</code>.
              </p>
              <div className="ops-toolbar">
                <label className="form__field ops-toolbar__field">
                  <span className="form__label">List id</span>
                  <input
                    value={listId}
                    onChange={(event) => setListId(event.target.value)}
                    disabled={refreshingStatus}
                    autoComplete="off"
                  />
                </label>
                <button
                  type="button"
                  className="button button--secondary ops-toolbar__btn"
                  disabled={refreshingStatus}
                  onClick={() => void loadStatusList()}
                >
                  {refreshingStatus ? (
                    <>
                      <span className="btn-spinner" aria-hidden="true" />
                      Refreshing…
                    </>
                  ) : (
                    "Refresh"
                  )}
                </button>
              </div>

              {refreshingStatus && !statusJson ? (
                <p className="hint">Loading status list…</p>
              ) : statusJson ? (
                <>
                  <div className="wallet-stats ops-stats">
                    <div className="wallet-stat">
                      <span className="wallet-stat__label">Capacity</span>
                      <span className="wallet-stat__value">{statusJson.capacity}</span>
                    </div>
                    <div className="wallet-stat">
                      <span className="wallet-stat__label">Allocated</span>
                      <span className="wallet-stat__value">{statusJson.allocated}</span>
                    </div>
                    <div className="wallet-stat">
                      <span className="wallet-stat__label">Purpose</span>
                      <span className="wallet-stat__value">{statusJson.statusPurpose}</span>
                    </div>
                    <div className="wallet-stat">
                      <span className="wallet-stat__label">Issued</span>
                      <span className="wallet-stat__value">
                        {new Date(statusJson.issuedAt).toLocaleString()}
                      </span>
                    </div>
                  </div>
                  {statusJwt ? (
                    <details className="json-panel ops-jwt-panel">
                      <summary>Status list JWT (wire format)</summary>
                      <pre className="raw-json__pre mono-sm">{statusJwt}</pre>
                    </details>
                  ) : null}
                  <JsonPanel title="Decoded list metadata" data={statusJson} />
                </>
              ) : null}
            </div>

            <div className="card">
              <h2 className="card__title">Check revocation index</h2>
              <p className="hint">
                After revoking on <Link to="/wallet">Wallet</Link>, enter the index here to confirm
                the bit flipped to <strong>REVOKED</strong> on the published list.
              </p>
              <div className="ops-toolbar">
                <label className="form__field ops-toolbar__field ops-toolbar__field--narrow">
                  <span className="form__label">Index</span>
                  <input
                    value={indexInput}
                    onChange={(event) => setIndexInput(event.target.value)}
                    disabled={lookingUpIndex}
                    inputMode="numeric"
                    autoComplete="off"
                  />
                </label>
                <button
                  type="button"
                  className="ops-toolbar__btn"
                  disabled={lookingUpIndex || refreshingStatus}
                  onClick={() => void lookupStatusIndex()}
                >
                  {lookingUpIndex ? (
                    <>
                      <span className="btn-spinner" aria-hidden="true" />
                      Checking…
                    </>
                  ) : (
                    "Check status"
                  )}
                </button>
              </div>
              {statusEntry ? (
                <div className="ops-entry-result">
                  <span className="wallet-stat__label">Index {statusEntry.index}</span>
                  <span
                    className={`status-badge status-badge--${statusEntry.status === "ACTIVE" ? "up" : "down"}`}
                  >
                    {statusEntry.status}
                  </span>
                  <p className="hint ops-entry-result__hint">
                    List <code>{statusEntry.listId}</code> · purpose {statusEntry.statusPurpose}
                  </p>
                </div>
              ) : (
                <p className="hint">Enter an index and click Check status.</p>
              )}
            </div>
          </>
        )}
      </div>
    </section>
  );
}
