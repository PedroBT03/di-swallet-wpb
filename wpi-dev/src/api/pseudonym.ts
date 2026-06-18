import type {
  CreatePseudonymRequest,
  PseudonymView,
  RegistrationFinishResponse,
  RegistrationOptionsResponse,
} from "../types/pseudonym";
import { apiFetch } from "./client";

export function listPseudonyms(
  holderId: string,
  authHeaders: Headers,
  rpId?: string,
): Promise<PseudonymView[]> {
  const params = new URLSearchParams({ holderId });
  if (rpId?.trim()) {
    params.set("rpId", rpId.trim());
  }
  return apiFetch<PseudonymView[]>(`/api/v1/wallet/pseudonyms?${params}`, {
    raw: true,
    headers: authHeaders,
  });
}

export function createPseudonym(
  request: CreatePseudonymRequest,
  authHeaders: Headers,
): Promise<PseudonymView> {
  return apiFetch<PseudonymView>("/api/v1/wallet/pseudonyms", {
    method: "POST",
    headers: authHeaders,
    body: JSON.stringify(request),
  });
}

export function deletePseudonym(
  id: string,
  holderId: string,
  authHeaders: Headers,
): Promise<void> {
  const params = new URLSearchParams({ holderId });
  return apiFetch<void>(`/api/v1/wallet/pseudonyms/${encodeURIComponent(id)}?${params}`, {
    method: "DELETE",
    raw: true,
    headers: authHeaders,
  });
}

export function fetchPseudonymRegistrationOptions(
  id: string,
  holderId: string,
  origin: string,
  authHeaders: Headers,
): Promise<RegistrationOptionsResponse> {
  return apiFetch<RegistrationOptionsResponse>(
    `/api/v1/wallet/pseudonyms/${encodeURIComponent(id)}/registration/options`,
    {
      method: "POST",
      headers: authHeaders,
      body: JSON.stringify({ holderId, origin }),
    },
  );
}

export function finishPseudonymRegistration(
  id: string,
  holderId: string,
  origin: string,
  clientDataJSON: string,
  authHeaders: Headers,
): Promise<RegistrationFinishResponse> {
  return apiFetch<RegistrationFinishResponse>(
    `/api/v1/wallet/pseudonyms/${encodeURIComponent(id)}/registration/finish`,
    {
      method: "POST",
      headers: authHeaders,
      body: JSON.stringify({ holderId, origin, clientDataJSON }),
    },
  );
}
