import type { StatusListEntry, StatusListJsonPayload } from "../types/statusList";
import { apiFetch } from "./client";

export function fetchStatusListJwt(listId: string): Promise<string> {
  return apiFetch<string>(`/api/v1/wallet/status-lists/${encodeURIComponent(listId)}`, {
    raw: true,
  });
}

export function fetchStatusListJson(listId: string): Promise<StatusListJsonPayload> {
  return apiFetch<StatusListJsonPayload>(
    `/api/v1/wallet/status-lists/${encodeURIComponent(listId)}?format=json`,
    { raw: true },
  );
}

export function fetchStatusListEntry(listId: string, index: number): Promise<StatusListEntry> {
  return apiFetch<StatusListEntry>(
    `/api/v1/wallet/status-lists/${encodeURIComponent(listId)}/entries/${index}`,
    { raw: true },
  );
}
