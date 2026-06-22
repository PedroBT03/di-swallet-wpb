import { useCallback, useEffect, useState } from "react";
import { fetchTrustMark, refreshTrustMark } from "../api/trustMark";
import {
  fetchStatusListEntry,
  fetchStatusListJson,
  fetchStatusListJwt,
} from "../api/statusList";
import { JsonPanel } from "../components/JsonPanel";
import { DEFAULT_STATUS_LIST_ID } from "../types/statusList";
import type { StatusListEntry, StatusListJsonPayload } from "../types/statusList";
import type { TrustMarkView } from "../types/trustMark";
import { formatApiError } from "../utils/apiError";

type OpsTab = "trust" | "status";

export function OpsPage() {
  const [tab, setTab] = useState<OpsTab>("trust");
  const [lang, setLang] = useState("en");
  const [trustMark, setTrustMark] = useState<TrustMarkView | null>(null);
  const [listId, setListId] = useState(DEFAULT_STATUS_LIST_ID);
  const [indexInput, setIndexInput] = useState("0");
  const [statusJwt, setStatusJwt] = useState<string | null>(null);
  const [statusJson, setStatusJson] = useState<StatusListJsonPayload | null>(null);
  const [statusEntry, setStatusEntry] = useState<StatusListEntry | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const loadTrustMark = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const view = await fetchTrustMark(lang.trim() || undefined);
      setTrustMark(view);
    } catch (err) {
      setTrustMark(null);
      setError(formatApiError(err));
    } finally {
      setLoading(false);
    }
  }, [lang]);

  useEffect(() => {
    if (tab === "trust") {
      void loadTrustMark();
    }
  }, [tab, loadTrustMark]);

  async function handleRefreshTrustMark() {
    setLoading(true);
    setError(null);
    try {
      const view = await refreshTrustMark();
      setTrustMark(view);
    } catch (err) {
      setError(formatApiError(err));
    } finally {
      setLoading(false);
    }
  }

  async function loadStatusList() {
    setLoading(true);
    setError(null);
    setStatusEntry(null);
    try {
      const [jwt, json] = await Promise.all([
        fetchStatusListJwt(listId.trim()),
        fetchStatusListJson(listId.trim()),
      ]);
      setStatusJwt(jwt);
      setStatusJson(json);
    } catch (err) {
      setStatusJwt(null);
      setStatusJson(null);
      setError(formatApiError(err));
    } finally {
      setLoading(false);
    }
  }

  async function lookupStatusIndex() {
    const index = Number.parseInt(indexInput.trim(), 10);
    if (Number.isNaN(index) || index < 0) {
      setError("Revocation index must be a non-negative integer.");
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const entry = await fetchStatusListEntry(listId.trim(), index);
      setStatusEntry(entry);
    } catch (err) {
      setStatusEntry(null);
      setError(formatApiError(err));
    } finally {
      setLoading(false);
    }
  }

  return (
    <section className="page">
      <header className="page__header">
        <h1>Ops</h1>
        <p className="page__lead">
          Public WPB surfaces for trust mark (TS1) and revocation status lists (verifier lookup).
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
          Status lists
        </button>
      </div>

      {tab === "trust" ? (
        <div className="card">
          <h2 className="card__title">EUDI Trust Mark</h2>
          <p className="hint">
            <code>GET /api/v1/wallet/trust-mark</code>. Public view for wallet solution certification
            metadata. Remote resource fetch may warn in dev when URLs are placeholders.
          </p>
          <div className="toolbar toolbar--compact">
            <label className="form__field form__field--inline">
              <span className="form__label">Language</span>
              <input value={lang} onChange={(event) => setLang(event.target.value)} disabled={loading} />
            </label>
            <button type="button" onClick={() => void loadTrustMark()} disabled={loading}>
              {loading ? "Loading…" : "Load trust mark"}
            </button>
            <button
              type="button"
              className="button button--secondary"
              onClick={() => void handleRefreshTrustMark()}
              disabled={loading}
            >
              Refresh cache
            </button>
          </div>
          {trustMark ? (
            <>
              {trustMark.userNotice ? (
                <div className="alert alert--info" role="status">
                  {trustMark.userNotice}
                </div>
              ) : null}
              {trustMark.warnings.length > 0 ? (
                <div className="alert alert--info" role="status">
                  <ul className="present-consent__list">
                    {trustMark.warnings.map((warning) => (
                      <li key={warning}>{warning}</li>
                    ))}
                  </ul>
                </div>
              ) : null}
              {trustMark.resource?.imageUrl ? (
                <p>
                  <img
                    src={trustMark.resource.imageUrl}
                    alt={trustMark.resource.imageName ?? "Trust mark"}
                    className="trust-mark__image"
                  />
                </p>
              ) : null}
              {trustMark.resource?.localizedText ? (
                <p className="hint">{trustMark.resource.localizedText}</p>
              ) : null}
              {trustMark.actions.length > 0 ? (
                <ul className="action-uri-list">
                  {trustMark.actions.map((action) => (
                    <li key={action.uri} className="action-uri-list__item">
                      <span className="action-uri-list__channel">{action.type}</span>
                      <a href={action.uri} target="_blank" rel="noreferrer">
                        {action.uri}
                      </a>
                    </li>
                  ))}
                </ul>
              ) : null}
              <JsonPanel title="Trust mark response" data={trustMark} />
            </>
          ) : null}
        </div>
      ) : (
        <div className="card">
          <h2 className="card__title">Status list publication</h2>
          <p className="hint">
            Verifiers poll <code>GET /status-lists/{"{listId}"}</code> for a Token Status List JWT, or
            check a single bit with <code>/entries/{"{index}"}</code>. Default list id is{" "}
            <code>{DEFAULT_STATUS_LIST_ID}</code> (credential and HSM key revocation indices).
          </p>
          <label className="form__field">
            <span className="form__label">List id</span>
            <input value={listId} onChange={(event) => setListId(event.target.value)} disabled={loading} />
          </label>
          <div className="toolbar toolbar--compact">
            <button type="button" onClick={() => void loadStatusList()} disabled={loading}>
              {loading ? "Loading…" : "Load published list"}
            </button>
          </div>
          {statusJson ? (
            <dl className="details-list">
              <div className="details-list__row">
                <dt>Capacity</dt>
                <dd>{statusJson.capacity}</dd>
              </div>
              <div className="details-list__row">
                <dt>Allocated</dt>
                <dd>{statusJson.allocated}</dd>
              </div>
            </dl>
          ) : null}
          {statusJwt ? (
            <details className="json-panel">
              <summary>Status list JWT</summary>
              <pre className="raw-json__pre">{statusJwt}</pre>
            </details>
          ) : null}
          {statusJson ? <JsonPanel title="JSON bitstring view" data={statusJson} /> : null}

          <h3 className="card__title">Entry lookup</h3>
          <p className="hint">
            Use the revocation index from Wallet → HSM key or credential metadata after sync.
          </p>
          <div className="toolbar toolbar--compact">
            <label className="form__field form__field--inline">
              <span className="form__label">Index</span>
              <input
                value={indexInput}
                onChange={(event) => setIndexInput(event.target.value)}
                disabled={loading}
              />
            </label>
            <button type="button" onClick={() => void lookupStatusIndex()} disabled={loading}>
              Lookup entry
            </button>
          </div>
          {statusEntry ? (
            <p>
              Index <code>{statusEntry.index}</code>:{" "}
              <span
                className={`status-badge status-badge--${statusEntry.status === "ACTIVE" ? "up" : "down"}`}
              >
                {statusEntry.status}
              </span>
            </p>
          ) : null}
        </div>
      )}
    </section>
  );
}
