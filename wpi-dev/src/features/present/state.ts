import type { CredentialChoiceGroup, PresentationState } from "../../types/openid4vp";

const TERMINAL_STATES: PresentationState[] = ["FAILED", "REJECTED", "DISPATCHED", "EXPIRED"];

export function isTerminalPresentationState(state: PresentationState): boolean {
  return TERMINAL_STATES.includes(state);
}

export function needsConsentScreen(state: PresentationState): boolean {
  return state === "CONSENT_PENDING";
}

/** Pre-selects the sole candidate in each group, or the stored credential over a synthetic demo. */
export function defaultCredentialSelection(groups: CredentialChoiceGroup[]): string[] {
  return groups.flatMap((group) => {
    if (group.candidates.length === 1) {
      return [group.candidates[0].candidateId];
    }
    const stored = group.candidates.find((candidate) => candidate.credentialId != null);
    if (stored) {
      return [stored.candidateId];
    }
    return [];
  });
}

export function allGroupsSelected(
  groups: CredentialChoiceGroup[],
  selectedIds: string[],
): boolean {
  return groups.every((group) => {
    if (group.candidates.length === 0) {
      return true;
    }
    return group.candidates.some((c) => selectedIds.includes(c.candidateId));
  });
}

export type PresentStep = "authorize" | "consent" | "outcome";

export function presentStepForState(state: PresentationState | null): PresentStep {
  if (!state) {
    return "authorize";
  }
  if (isTerminalPresentationState(state)) {
    return "outcome";
  }
  if (state === "CONSENT_PENDING") {
    return "consent";
  }
  if (
    state === "CONSENT_GRANTED" ||
    state === "VP_BUILT" ||
    state === "DISPATCHED"
  ) {
    return "outcome";
  }
  return "authorize";
}

export function stateBadgeVariant(
  state: PresentationState,
  error?: { code: string; message: string } | null,
): "up" | "down" | "unknown" {
  if (state === "DISPATCHED" && error) {
    return "down";
  }
  if (state === "DISPATCHED" || state === "CONSENT_GRANTED" || state === "VP_BUILT") {
    return "up";
  }
  if (state === "FAILED" || state === "REJECTED" || state === "EXPIRED") {
    return "down";
  }
  return "unknown";
}
