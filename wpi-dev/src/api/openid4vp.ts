import type {
  AuthorizationStartRequest,
  ConsentSubmission,
  PresentationConsentView,
  PresentationContext,
  SessionEvent,
} from "../types/openid4vp";
import { apiFetch } from "./client";

export function startPresentation(request: AuthorizationStartRequest): Promise<PresentationContext> {
  return apiFetch<PresentationContext>("/openid4vp/authorize", {
    method: "POST",
    body: JSON.stringify(request),
  });
}

export function fetchConsentView(
  sessionId: string,
  holderId: string,
  authHeaders: Headers,
): Promise<PresentationConsentView> {
  const params = new URLSearchParams({ holderId });
  return apiFetch<PresentationConsentView>(
    `/openid4vp/session/${encodeURIComponent(sessionId)}/consent-view?${params}`,
    { raw: true, headers: authHeaders },
  );
}

export function submitConsent(
  submission: ConsentSubmission,
  authHeaders: Headers,
): Promise<PresentationContext> {
  return apiFetch<PresentationContext>("/openid4vp/consent", {
    method: "POST",
    headers: authHeaders,
    body: JSON.stringify(submission),
  });
}

export function fetchPresentationSession(
  sessionId: string,
  authHeaders: Headers,
): Promise<PresentationContext> {
  return apiFetch<PresentationContext>(`/openid4vp/session/${encodeURIComponent(sessionId)}`, {
    raw: true,
    headers: authHeaders,
  });
}

export function fetchPresentationEvents(
  sessionId: string,
  authHeaders: Headers,
): Promise<SessionEvent[]> {
  return apiFetch<SessionEvent[]>(
    `/openid4vp/session/${encodeURIComponent(sessionId)}/events`,
    { raw: true, headers: authHeaders },
  );
}
