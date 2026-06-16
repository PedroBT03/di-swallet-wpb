import type { UserDeviceRecord } from "../types/wallet";
import type { AuthChallengeResponse } from "../types/fido2";
import { apiFetch } from "./client";

export function fetchAuthChallenge(holderId: string): Promise<AuthChallengeResponse> {
  return apiFetch<AuthChallengeResponse>(`/api/v1/wallet/auth/challenge/${encodeURIComponent(holderId)}`, {
    raw: true,
  });
}

export function registerDevice(
  holderId: string,
  credentialId: string,
  publicKeyBase64: string,
): Promise<UserDeviceRecord> {
  const params = new URLSearchParams({
    credentialId,
    publicKeyBase64,
  });
  return apiFetch<UserDeviceRecord>(
    `/api/v1/wallet/auth/register/${encodeURIComponent(holderId)}?${params}`,
    {
      method: "POST",
      raw: true,
    },
  );
}
