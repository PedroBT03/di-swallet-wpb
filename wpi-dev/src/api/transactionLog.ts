import type {
  TransactionExportRequest,
  TransactionLogSummary,
  Ts10Transaction,
} from "../types/transactionLog";
import { apiFetch } from "./client";

function mergeHeaders(authHeaders: Headers, logKeyBase64?: string | null): Headers {
  const headers = new Headers(authHeaders);
  if (logKeyBase64) {
    headers.set("X-Wallet-Log-Key", logKeyBase64);
  }
  return headers;
}

export function fetchTransactions(
  holderId: string,
  authHeaders: Headers,
): Promise<TransactionLogSummary[]> {
  return apiFetch<TransactionLogSummary[]>(
    `/api/v1/wallet/transactions?holderId=${encodeURIComponent(holderId)}`,
    { raw: true, headers: authHeaders },
  );
}

export function fetchTransaction(
  holderId: string,
  transactionId: string,
  authHeaders: Headers,
  logKeyBase64?: string | null,
): Promise<Ts10Transaction> {
  return apiFetch<Ts10Transaction>(
    `/api/v1/wallet/transactions/${encodeURIComponent(transactionId)}?holderId=${encodeURIComponent(holderId)}`,
    { raw: true, headers: mergeHeaders(authHeaders, logKeyBase64) },
  );
}

export function deleteTransaction(
  holderId: string,
  transactionId: string,
  authHeaders: Headers,
): Promise<{ transactionId: string; status: string }> {
  return apiFetch<{ transactionId: string; status: string }>(
    `/api/v1/wallet/transactions/${encodeURIComponent(transactionId)}?holderId=${encodeURIComponent(holderId)}`,
    { method: "DELETE", raw: true, headers: authHeaders },
  );
}

export function exportTransactionsJwe(
  request: TransactionExportRequest,
  authHeaders: Headers,
  logKeyBase64?: string | null,
): Promise<string> {
  return apiFetch<string>("/api/v1/wallet/transactions/export", {
    method: "POST",
    headers: mergeHeaders(authHeaders, logKeyBase64),
    body: JSON.stringify(request),
  });
}
