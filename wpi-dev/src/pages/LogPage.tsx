import { useCallback, useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import {
  deleteTransaction,
  exportTransactionsJwe,
  fetchTransaction,
  fetchTransactions,
} from "../api/transactionLog";
import { fetchWpbOperationalInfo, parseTransactionLogDekMode } from "../api/ops";
import { AuthGate } from "../components/AuthGate";
import { AuthenticatingBanner } from "../components/AuthenticatingBanner";
import { JsonPanel } from "../components/JsonPanel";
import { bytesToBase64, deriveHolderLogKey } from "../crypto/holderLogKey";
import {
  clearLogKeyBase64,
  loadLogKeyBase64,
  saveLogKeyBase64,
} from "../features/log/logKeySession";
import {
  downloadTextFile,
  formatOccurredAt,
  logAccessCopy,
  resultBadgeVariant,
  sortNewestFirst,
  transactionTypeLabel,
} from "../features/log/state";
import { useAuthedApi } from "../hooks/useAuthedApi";
import type { LogTransactionDetail, TransactionLogSummary } from "../types/transactionLog";
import { formatApiError } from "../utils/apiError";

export function LogPage() {
  const { session, withApiAuth, withSoleControl, busy, clearError } = useAuthedApi();
  const holderId = session?.holderId.trim() ?? "";

  const [dekMode, setDekMode] = useState<"server" | "holder" | null>(null);
  const [logPassphrase, setLogPassphrase] = useState("");
  const [logKeyReady, setLogKeyReady] = useState(() => loadLogKeyBase64() != null);
  const [transactions, setTransactions] = useState<TransactionLogSummary[] | null>(null);
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const [activeId, setActiveId] = useState<string | null>(null);
  const [detail, setDetail] = useState<LogTransactionDetail | null>(null);
  const [exportPassword, setExportPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [lastRaw, setLastRaw] = useState<unknown>(null);
  const [showLogUnlock, setShowLogUnlock] = useState(false);
  const [refreshing, setRefreshing] = useState(false);

  const holderDekMode = dekMode === "holder";
  const needsLogKey = holderDekMode && !logKeyReady;
  const logAccess = logAccessCopy(dekMode);

  const refreshDekMode = useCallback(() => {
    fetchWpbOperationalInfo()
      .then((info) => setDekMode(parseTransactionLogDekMode(info)))
      .catch(() => setDekMode(null));
  }, []);

  useEffect(() => {
    refreshDekMode();
  }, [refreshDekMode]);

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

  async function unlockLogKey() {
    if (!holderId || !logPassphrase.trim()) {
      setError("Enter your log passphrase.");
      return;
    }
    const derived = await deriveHolderLogKey(holderId, logPassphrase);
    saveLogKeyBase64(bytesToBase64(derived));
    setLogKeyReady(true);
    setLogPassphrase("");
    setShowLogUnlock(false);
  }

  function forgetLogKey() {
    clearLogKeyBase64();
    setLogKeyReady(false);
    setDetail(null);
    setActiveId(null);
    setShowLogUnlock(false);
  }

  function promptLogUnlock(message: string) {
    setShowLogUnlock(true);
    setError(message);
  }

  const refreshList = useCallback(async () => {
    await withApiAuth(async (headers) => {
      const list = await fetchTransactions(holderId, headers);
      setTransactions(sortNewestFirst(list));
      setSelectedIds(list.map((entry) => entry.transactionId));
      setLastRaw(list);
    });
  }, [holderId, withApiAuth]);

  const refreshListRef = useRef(refreshList);
  refreshListRef.current = refreshList;

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
        await refreshListRef.current();
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
  }, [holderId, clearError]);

  async function handleRefresh() {
    setRefreshing(true);
    try {
      await refreshList();
    } finally {
      setRefreshing(false);
    }
  }

  async function loadDetail(transactionId: string) {
    const logKey = loadLogKeyBase64();
    if (needsLogKey) {
      promptLogUnlock("Enter your log passphrase to view transaction details.");
      return;
    }
    await withApiAuth(async (headers) => {
      const tx = await fetchTransaction(holderId, transactionId, headers, logKey);
      setActiveId(transactionId);
      setDetail(tx);
      setLastRaw(tx);
    });
  }

  async function markDeleted(transactionId: string) {
    await withSoleControl(async (headers) => {
      const result = await deleteTransaction(holderId, transactionId, headers);
      setLastRaw(result);
      await refreshList();
      if (activeId === transactionId) {
        setActiveId(null);
        setDetail(null);
      }
    });
  }

  async function exportJwe() {
    if (!exportPassword.trim()) {
      setError("Enter an export password.");
      return;
    }
    const logKey = loadLogKeyBase64();
    if (needsLogKey) {
      promptLogUnlock("Enter your log passphrase before exporting.");
      return;
    }
    await withApiAuth(async (headers) => {
      const jwe = await exportTransactionsJwe(
        {
          holderId,
          transactionIds: selectedIds,
          password: exportPassword,
        },
        headers,
        logKey,
      );
      const stamp = new Date().toISOString().replace(/[:.]/g, "-");
      downloadTextFile(`transactions-${holderId}-${stamp}.jwe`, jwe, "application/jwe");
      setLastRaw({ exported: selectedIds.length || "all", bytes: jwe.length });
    });
  }

  function toggleSelected(transactionId: string) {
    setSelectedIds((current) =>
      current.includes(transactionId)
        ? current.filter((id) => id !== transactionId)
        : [...current, transactionId],
    );
  }

  return (
    <AuthGate>
      <section className="page page--log">
        <header className="page__header">
          <h1>Transaction log</h1>
          <p className="page__lead">
            Review presentations, issuances, and other wallet activity. Open a row for the full
            record, or export selected entries as a password-protected file.
          </p>
        </header>

        {busy ? <AuthenticatingBanner /> : null}
        {error ? <div className="alert alert--error">{error}</div> : null}

        <div className="page-stack">
        <div className="card log-access-card">
          <h2 className="card__title">Log access</h2>
          <p className="hint log-access-hint">
            <strong className="log-access-hint__summary">{logAccess.summary}</strong>
            <span className="log-access-hint__detail">{logAccess.detail}</span>
          </p>
          <div className="toolbar log-access-card__toolbar">
            <button
              type="button"
              className="button button--secondary"
              disabled={busy || refreshing || !holderId}
              onClick={() => void runAction(handleRefresh)}
            >
              {refreshing ? "Refreshing…" : "Refresh"}
            </button>
          </div>
        </div>

        {holderId ? (
          <div className="card">
            <h2 className="card__title">
              Entries{transactions ? ` (${transactions.length})` : ""}
            </h2>
            {refreshing && transactions === null ? (
              <p className="hint">Loading transactions…</p>
            ) : transactions && transactions.length === 0 ? (
              <p className="hint">
                No transactions yet. Run a <Link to="/present">Present</Link> or{" "}
                <Link to="/issue">Issue</Link> flow first.
              </p>
            ) : transactions && transactions.length > 0 ? (
              <div className="log-entries__scroll panel-scroll">
                <div className="table-wrap">
                  <table className="data-table">
                  <thead>
                    <tr>
                      <th scope="col">Export</th>
                      <th scope="col">Type</th>
                      <th scope="col">Result</th>
                      <th scope="col">Time</th>
                      <th scope="col">Actions</th>
                    </tr>
                  </thead>
                  <tbody>
                    {transactions.map((entry) => (
                      <tr key={entry.transactionId} className={activeId === entry.transactionId ? "is-active" : ""}>
                        <td>
                          <input
                            type="checkbox"
                            checked={selectedIds.includes(entry.transactionId)}
                            onChange={() => toggleSelected(entry.transactionId)}
                            aria-label={`Include ${entry.transactionId} in export`}
                          />
                        </td>
                        <td>
                          <button
                            type="button"
                            className="link-button"
                            onClick={() => void runAction(() => loadDetail(entry.transactionId))}
                          >
                            {transactionTypeLabel(entry.transactionType)}
                          </button>
                          <div className="hint mono-sm">{entry.transactionId}</div>
                        </td>
                        <td>
                          <span className={`status-badge status-badge--${resultBadgeVariant(entry.transactionResult)}`}>
                            {entry.transactionResult}
                          </span>
                        </td>
                        <td>{formatOccurredAt(entry.occurredAt)}</td>
                        <td>
                          <button
                            type="button"
                            className="button button--secondary button--sm"
                            disabled={busy}
                            onClick={() => void runAction(() => markDeleted(entry.transactionId))}
                          >
                            Delete
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                </div>
              </div>
            ) : null}
          </div>
        ) : null}

        {showLogUnlock && !logKeyReady ? (
          <div className="card">
            <h2 className="card__title">Log passphrase</h2>
            <p className="hint">
              Required in production mode to decrypt transaction details on the server.
            </p>
            <div className="form">
              <label className="form__field">
                <span className="form__label">Log passphrase</span>
                <input
                  type="password"
                  value={logPassphrase}
                  onChange={(event) => setLogPassphrase(event.target.value)}
                  autoComplete="current-password"
                />
              </label>
              <div className="toolbar">
                <button type="button" disabled={busy} onClick={() => void runAction(unlockLogKey)}>
                  Derive log key
                </button>
              </div>
            </div>
          </div>
        ) : null}

        {logKeyReady ? (
          <p className="hint">
            Log key ready for this browser session.{" "}
            <button type="button" className="link-button" onClick={forgetLogKey}>
              Forget log key
            </button>
          </p>
        ) : null}

        {detail ? (
          <div className="card">
            <h2 className="card__title">Transaction detail</h2>
            <JsonPanel title="Full record" data={detail} />
          </div>
        ) : null}

        {transactions && transactions.length > 0 ? (
          <div className="card">
            <h2 className="card__title">Export JWE</h2>
            <p className="hint">
              Exports {selectedIds.length || transactions.length} selected transaction(s) as a
              password-protected JWE file.
            </p>
            <div className="form">
              <label className="form__field">
                <span className="form__label">Export password</span>
                <input
                  type="password"
                  value={exportPassword}
                  onChange={(event) => setExportPassword(event.target.value)}
                  autoComplete="new-password"
                />
              </label>
              <div className="toolbar">
                <button
                  type="button"
                  disabled={busy || needsLogKey}
                  onClick={() => void runAction(exportJwe)}
                >
                  Download JWE
                </button>
              </div>
            </div>
          </div>
        ) : null}

        {lastRaw ? <JsonPanel title="Last API response" data={lastRaw} /> : null}
        </div>
      </section>
    </AuthGate>
  );
}
