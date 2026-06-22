import { useCallback, useEffect, useState } from "react";
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
  resultBadgeVariant,
  sortNewestFirst,
  transactionTypeLabel,
} from "../features/log/state";
import { useAuthedApi } from "../hooks/useAuthedApi";
import type { TransactionLogSummary, Ts10Transaction } from "../types/transactionLog";
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
  const [detail, setDetail] = useState<Ts10Transaction | null>(null);
  const [exportPassword, setExportPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [lastRaw, setLastRaw] = useState<unknown>(null);

  const holderDekMode = dekMode === "holder";
  const needsLogKey = holderDekMode && !logKeyReady;

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
  }

  function forgetLogKey() {
    clearLogKeyBase64();
    setLogKeyReady(false);
    setDetail(null);
    setActiveId(null);
  }

  async function loadList() {
    await withApiAuth(async (headers) => {
      const list = await fetchTransactions(holderId, headers);
      setTransactions(sortNewestFirst(list));
      setSelectedIds(list.map((entry) => entry.transactionId));
      setLastRaw(list);
    });
  }

  async function loadDetail(transactionId: string) {
    const logKey = loadLogKeyBase64();
    if (needsLogKey) {
      setError("Unlock the transaction log with your passphrase first.");
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
      await loadList();
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
      setError("Unlock the transaction log with your passphrase first.");
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
      <section className="page">
        <header className="page__header">
          <h1>Transaction log</h1>
          <p className="page__lead">
            Holder transaction history, detail view, and password-protected JWE export.
          </p>
        </header>

        {busy ? <AuthenticatingBanner /> : null}
        {error ? <div className="alert alert--error">{error}</div> : null}

        <div className="card">
          <h2 className="card__title">Log access</h2>
          <p className="hint">
            DEK mode: <code>{dekMode ?? "unknown"}</code>
            {holderDekMode ? (
              <>
                . WPB cannot decrypt payloads without <code>X-Wallet-Log-Key</code> from this UI.
              </>
            ) : (
              <>. Server-side DEK (typical dev profile).</>
            )}
          </p>
          {holderDekMode ? (
            <div className="form">
              <label className="form__field">
                <span className="form__label">Log passphrase</span>
                <input
                  type="password"
                  value={logPassphrase}
                  onChange={(event) => setLogPassphrase(event.target.value)}
                  autoComplete="current-password"
                  disabled={logKeyReady}
                />
              </label>
              <div className="toolbar">
                <button
                  type="button"
                  disabled={busy || logKeyReady}
                  onClick={() => void runAction(unlockLogKey)}
                >
                  Derive log key
                </button>
                {logKeyReady ? (
                  <button type="button" className="button button--secondary" onClick={forgetLogKey}>
                    Forget log key
                  </button>
                ) : null}
              </div>
              {logKeyReady ? (
                <p className="hint">Log key ready for this browser session.</p>
              ) : null}
            </div>
          ) : null}
          <div className="toolbar">
            <button
              type="button"
              disabled={busy || !holderId}
              onClick={() => void runAction(loadList)}
            >
              Load transactions
            </button>
          </div>
        </div>

        {transactions ? (
          <div className="card">
            <h2 className="card__title">Entries ({transactions.length})</h2>
            {transactions.length === 0 ? (
              <p className="hint">
                No transactions yet. Run a <Link to="/present">Present</Link> or{" "}
                <Link to="/issue">Issue</Link> flow first.
              </p>
            ) : (
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
            )}
          </div>
        ) : null}

        {detail ? (
          <div className="card">
            <h2 className="card__title">Transaction detail</h2>
            <JsonPanel title="TS10 payload" data={detail} />
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
      </section>
    </AuthGate>
  );
}
