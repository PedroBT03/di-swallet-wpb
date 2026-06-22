import type { UserDeviceRecord } from "../types/wallet";
import type { AuthChallengeResponse, Fido2AssertionPayload } from "../types/fido2";
import { apiFetch } from "./client";

export interface HolderSessionResponse {
  sessionToken: string;
  holderId: string;
  expiresAt: string;
}

export function fetchAuthChallenge(
  holderId: string,
  credentialId?: string,
): Promise<AuthChallengeResponse> {
  const params = new URLSearchParams();
  if (credentialId?.trim()) {
    params.set("credentialId", credentialId.trim());
  }
  const query = params.size > 0 ? `?${params}` : "";
  return apiFetch<AuthChallengeResponse>(
    `/api/v1/wallet/auth/challenge/${encodeURIComponent(holderId)}${query}`,
    { raw: true },
  );
}

export function createHolderSession(
  assertion: Fido2AssertionPayload,
): Promise<HolderSessionResponse> {
  return apiFetch<HolderSessionResponse>("/api/v1/wallet/auth/session", {
    method: "POST",
    body: JSON.stringify(assertion),
  });
}

export function registerDevice(
  holderId: string,
  credentialId: string,
  publicKeyBase64: string,
  replaceExisting = false,
): Promise<UserDeviceRecord> {
  const params = new URLSearchParams({
    credentialId,
    publicKeyBase64,
    replaceExisting: String(replaceExisting),
  });
  return apiFetch<UserDeviceRecord>(
    `/api/v1/wallet/auth/register/${encodeURIComponent(holderId)}?${params}`,
    {
      method: "POST",
      raw: true,
    },
  );
}
