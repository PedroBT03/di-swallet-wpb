import type {
  DataDeletionInitiateRequest,
  DataDeletionInitiateResponse,
  DpaReportInitiateRequest,
  DpaReportInitiateResponse,
  EligibleDpaReportPresentation,
  EligiblePresentation,
} from "../types/privacy";
import { loadLogKeyBase64 } from "../features/log/logKeySession";
import { apiFetch } from "./client";

function withLogKey(authHeaders: Headers): Headers {
  const headers = new Headers(authHeaders);
  const logKey = loadLogKeyBase64();
  if (logKey) {
    headers.set("X-Wallet-Log-Key", logKey);
  }
  return headers;
}

export function fetchEligibleDeletions(
  holderId: string,
  authHeaders: Headers,
): Promise<EligiblePresentation[]> {
  return apiFetch<EligiblePresentation[]>(
    `/api/v1/wallet/deletion-requests/eligible?holderId=${encodeURIComponent(holderId)}`,
    { raw: true, headers: withLogKey(authHeaders) },
  );
}

export function initiateDeletion(
  request: DataDeletionInitiateRequest,
  authHeaders: Headers,
): Promise<DataDeletionInitiateResponse> {
  return apiFetch<DataDeletionInitiateResponse>("/api/v1/wallet/deletion-requests", {
    method: "POST",
    headers: withLogKey(authHeaders),
    body: JSON.stringify(request),
  });
}

export function fetchEligibleDpaReports(
  holderId: string,
  authHeaders: Headers,
): Promise<EligibleDpaReportPresentation[]> {
  return apiFetch<EligibleDpaReportPresentation[]>(
    `/api/v1/wallet/dpa-reports/eligible?holderId=${encodeURIComponent(holderId)}`,
    { raw: true, headers: withLogKey(authHeaders) },
  );
}

export function initiateDpaReport(
  request: DpaReportInitiateRequest,
  authHeaders: Headers,
): Promise<DpaReportInitiateResponse> {
  return apiFetch<DpaReportInitiateResponse>("/api/v1/wallet/dpa-reports", {
    method: "POST",
    headers: withLogKey(authHeaders),
    body: JSON.stringify(request),
  });
}
