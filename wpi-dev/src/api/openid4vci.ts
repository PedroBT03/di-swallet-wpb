import type {
  IssuanceConsentSubmission,
  IssuanceConsentView,
  IssuanceContext,
  IssuanceEvent,
  NotificationEvent,
  OfferResolveRequest,
} from "../types/openid4vci";
import { apiFetch } from "./client";

export function resolveOffer(request: OfferResolveRequest): Promise<IssuanceContext> {
  return apiFetch<IssuanceContext>("/openid4vci/offer/resolve", {
    method: "POST",
    body: JSON.stringify(request),
  });
}

export function prepareAuthorization(sessionId: string): Promise<IssuanceContext> {
  return apiFetch<IssuanceContext>("/openid4vci/authorize/prepare", {
    method: "POST",
    body: JSON.stringify({ sessionId }),
  });
}

export function completeAuthorizationCode(
  sessionId: string,
  authorizationCode: string,
  state: string,
): Promise<IssuanceContext> {
  return apiFetch<IssuanceContext>("/openid4vci/authorize/code", {
    method: "POST",
    body: JSON.stringify({ sessionId, authorizationCode, state }),
  });
}

export function completePreAuthorized(
  sessionId: string,
  txCode?: string,
): Promise<IssuanceContext> {
  return apiFetch<IssuanceContext>("/openid4vci/authorize/pre-authorized", {
    method: "POST",
    body: JSON.stringify({ sessionId, txCode: txCode ?? null }),
  });
}

export function requestCredential(
  sessionId: string,
  credentialConfigurationId: string,
): Promise<IssuanceContext> {
  return apiFetch<IssuanceContext>("/openid4vci/credential/request", {
    method: "POST",
    body: JSON.stringify({ sessionId, credentialConfigurationId }),
  });
}

export function queryDeferred(sessionId: string): Promise<IssuanceContext> {
  return apiFetch<IssuanceContext>("/openid4vci/deferred/query", {
    method: "POST",
    body: JSON.stringify({ sessionId }),
  });
}

export function notifyIssuer(
  sessionId: string,
  event: NotificationEvent,
  description?: string,
): Promise<IssuanceContext> {
  return apiFetch<IssuanceContext>("/openid4vci/notify", {
    method: "POST",
    body: JSON.stringify({ sessionId, event, description }),
  });
}

export function fetchIssuanceConsentView(
  sessionId: string,
  holderId: string,
  authHeaders: Headers,
): Promise<IssuanceConsentView> {
  const params = new URLSearchParams({ holderId });
  return apiFetch<IssuanceConsentView>(
    `/openid4vci/session/${encodeURIComponent(sessionId)}/consent-view?${params}`,
    { raw: true, headers: authHeaders },
  );
}

export function submitIssuanceConsent(
  submission: IssuanceConsentSubmission,
  authHeaders: Headers,
): Promise<IssuanceContext> {
  return apiFetch<IssuanceContext>("/openid4vci/consent", {
    method: "POST",
    headers: authHeaders,
    body: JSON.stringify(submission),
  });
}

export function fetchIssuanceSession(
  sessionId: string,
  authHeaders: Headers,
): Promise<IssuanceContext> {
  return apiFetch<IssuanceContext>(`/openid4vci/session/${encodeURIComponent(sessionId)}`, {
    raw: true,
    headers: authHeaders,
  });
}

export function fetchIssuanceEvents(
  sessionId: string,
  authHeaders: Headers,
): Promise<IssuanceEvent[]> {
  return apiFetch<IssuanceEvent[]>(
    `/openid4vci/session/${encodeURIComponent(sessionId)}/events`,
    { raw: true, headers: authHeaders },
  );
}
