import type { AuthorizationFlowKind, IssuanceContext, IssuanceState } from "../../types/openid4vci";

export type IssuanceStep = "offer" | "authorize" | "credential" | "consent" | "outcome";

const TERMINAL_STATES: IssuanceState[] = [
  "CREDENTIAL_ISSUED",
  "DEFERRED_ISSUED",
  "NOTIFIED",
  "FAILED",
  "REJECTED",
  "EXPIRED",
];

export function isTerminalIssuanceState(state: IssuanceState): boolean {
  return TERMINAL_STATES.includes(state);
}

export function isIssuanceSuccess(state: IssuanceState): boolean {
  return state === "CREDENTIAL_ISSUED" || state === "DEFERRED_ISSUED" || state === "NOTIFIED";
}

export function needsIssuanceConsentScreen(state: IssuanceState): boolean {
  return state === "ISSUANCE_CONSENT_PENDING";
}

export function issuanceStepForState(state: IssuanceState | null): IssuanceStep {
  if (!state) {
    return "offer";
  }
  if (isTerminalIssuanceState(state)) {
    return "outcome";
  }
  if (state === "ISSUANCE_CONSENT_PENDING") {
    return "consent";
  }
  if (state === "CREDENTIAL_REQUESTED" || state === "DEFERRED_PENDING") {
    return "credential";
  }
  if (
    state === "OFFER_RECEIVED" ||
    state === "OFFER_RESOLVED" ||
    state === "AUTHORIZATION_PREPARED" ||
    state === "AUTHORIZED"
  ) {
    return "authorize";
  }
  return "offer";
}

export function stateBadgeVariant(state: IssuanceState): "up" | "down" | "unknown" {
  if (isIssuanceSuccess(state)) {
    return "up";
  }
  if (state === "FAILED" || state === "REJECTED" || state === "EXPIRED") {
    return "down";
  }
  return "unknown";
}

export function primaryCredentialConfigurationId(ctx: IssuanceContext): string {
  return (
    ctx.credentialConfigurationIds[0] ??
    ctx.resolvedOffer?.credentialConfigurationIds[0] ??
    "pid_jwt"
  );
}

export function flowKind(ctx: IssuanceContext): AuthorizationFlowKind | null {
  return ctx.flow ?? ctx.resolvedOffer?.authorizationFlow ?? null;
}

export function continueActionLabel(ctx: IssuanceContext): string | null {
  switch (ctx.state) {
    case "OFFER_RESOLVED":
      return flowKind(ctx) === "PRE_AUTHORIZED_CODE"
        ? "Complete pre-authorization"
        : "Prepare authorization";
    case "AUTHORIZATION_PREPARED":
      return "Exchange authorization code";
    case "AUTHORIZED":
      return "Request credential";
    case "DEFERRED_PENDING":
      return "Poll deferred credential";
    case "CREDENTIAL_ISSUED":
    case "DEFERRED_ISSUED":
      return "Notify issuer";
    default:
      return null;
  }
}

export function canContinueIssuance(ctx: IssuanceContext | null): boolean {
  if (!ctx || ctx.error) {
    return false;
  }
  return continueActionLabel(ctx) != null;
}
