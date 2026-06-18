import type { TrustMarkView } from "../types/trustMark";
import { apiFetch } from "./client";

export function fetchTrustMark(lang?: string): Promise<TrustMarkView> {
  const params = lang ? `?lang=${encodeURIComponent(lang)}` : "";
  return apiFetch<TrustMarkView>(`/api/v1/wallet/trust-mark${params}`, { raw: true });
}

export function refreshTrustMark(): Promise<TrustMarkView> {
  return apiFetch<TrustMarkView>("/api/v1/wallet/trust-mark/refresh", {
    method: "POST",
    raw: true,
  });
}
