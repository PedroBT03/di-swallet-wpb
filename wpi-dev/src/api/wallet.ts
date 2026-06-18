import type {
  CredentialMutationResult,
  RevokeKeyResult,
  SignResult,
  WalletCredentialRecord,
  WalletInitRequest,
  WalletInitResult,
  WalletKeyRecord,
  WalletSummaryResponse,
} from "../types/wallet";
import { apiFetch } from "./client";

export function fetchWalletSummary(
  holderId: string,
  authHeaders: Headers,
): Promise<WalletSummaryResponse> {
  return apiFetch<WalletSummaryResponse>(
    `/api/v1/wallet/summary/${encodeURIComponent(holderId)}`,
    {
      raw: true,
      headers: authHeaders,
    },
  );
}

export function initWalletUnit(request: WalletInitRequest): Promise<WalletInitResult> {
  return apiFetch<WalletInitResult>("/api/v1/wallet/init", {
    method: "POST",
    body: JSON.stringify(request),
  });
}

export function createWalletKey(holderId: string, authHeaders: Headers): Promise<WalletKeyRecord> {
  return apiFetch<WalletKeyRecord>(`/api/v1/wallet/keys/${encodeURIComponent(holderId)}`, {
    method: "POST",
    headers: authHeaders,
    raw: true,
  });
}

export function getWalletKey(holderId: string, authHeaders: Headers): Promise<WalletKeyRecord> {
  return apiFetch<WalletKeyRecord>(`/api/v1/wallet/keys/${encodeURIComponent(holderId)}`, {
    raw: true,
    headers: authHeaders,
  });
}

export function revokeWalletKey(holderId: string, authHeaders: Headers): Promise<RevokeKeyResult> {
  return apiFetch<RevokeKeyResult>(
    `/api/v1/wallet/keys/${encodeURIComponent(holderId)}/revoke`,
    {
      method: "POST",
      headers: authHeaders,
      raw: true,
    },
  );
}

export function listCredentials(
  holderId: string,
  authHeaders: Headers,
): Promise<WalletCredentialRecord[]> {
  return apiFetch<WalletCredentialRecord[]>(
    `/api/v1/wallet/credentials/${encodeURIComponent(holderId)}`,
    {
      raw: true,
      headers: authHeaders,
    },
  );
}

export function signData(
  holderId: string,
  data: string,
  authHeaders: Headers,
): Promise<SignResult> {
  return apiFetch<SignResult>(`/api/v1/wallet/sign/${encodeURIComponent(holderId)}`, {
    method: "POST",
    headers: authHeaders,
    body: JSON.stringify({ data }),
  });
}

export function issueDemoSdCredential(
  holderId: string,
  authHeaders: Headers,
): Promise<WalletCredentialRecord> {
  return apiFetch<WalletCredentialRecord>(
    `/api/v1/wallet/credentials/issue-sd/${encodeURIComponent(holderId)}`,
    {
      method: "POST",
      headers: authHeaders,
      raw: true,
    },
  );
}

/** Permanently removes a credential from the holder wallet (DASH_05a). */
export function deleteCredential(
  credentialId: number,
  authHeaders: Headers,
): Promise<CredentialMutationResult> {
  return apiFetch<CredentialMutationResult>(
    `/api/v1/wallet/credentials/${credentialId}`,
    {
      method: "DELETE",
      headers: authHeaders,
      raw: true,
    },
  );
}

/** Revokes a WP-managed credential on the status list without deleting the row. */
export function revokeCredential(
  credentialId: number,
  authHeaders: Headers,
): Promise<CredentialMutationResult> {
  return apiFetch<CredentialMutationResult>(
    `/api/v1/wallet/credentials/${credentialId}/revoke`,
    {
      method: "POST",
      headers: authHeaders,
      raw: true,
    },
  );
}
