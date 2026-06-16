import type { WalletKeySummary } from "../types/fido2";
import { apiFetch } from "./client";

export function getWalletKey(holderId: string, authHeaders: Headers): Promise<WalletKeySummary> {
  return apiFetch<WalletKeySummary>(`/api/v1/wallet/keys/${encodeURIComponent(holderId)}`, {
    raw: true,
    headers: authHeaders,
  });
}
