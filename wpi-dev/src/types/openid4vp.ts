export type CredentialFormat = "SD_JWT" | "MDOC";

export type PresentationState =
  | "RECEIVED"
  | "REQUEST_RESOLVED"
  | "VERIFIER_VALIDATED"
  | "POLICY_EVALUATED"
  | "CONSENT_PENDING"
  | "CONSENT_GRANTED"
  | "VP_BUILT"
  | "DISPATCHED"
  | "FAILED"
  | "REJECTED"
  | "EXPIRED";

export type MinimizationLevel = "OK" | "WARNING";

export type ApprovalMode = "ALL_OR_NOTHING";

export interface ConsentWarning {
  code: string;
  message: string;
}

export interface MinimizationAssessment {
  level: MinimizationLevel;
  warnings: ConsentWarning[];
}

export interface VerifierConsentInfo {
  clientId: string;
  displayName: string | null;
  trusted: boolean;
  trustReason: string | null;
}

export interface ClaimConsentItem {
  path: string;
  label: string;
}

export interface CredentialChoiceOption {
  candidateId: string;
  credentialId: number | null;
  label: string;
  deviceBound: boolean;
}

export interface CredentialChoiceGroup {
  queryId: string;
  credentialType: string;
  format: CredentialFormat;
  candidates: CredentialChoiceOption[];
  requiresUserSelection: boolean;
}

export interface QueryConsentItem {
  queryId: string;
  format: CredentialFormat;
  credentialTypeHints: string[];
  requestedClaims: ClaimConsentItem[];
}

export interface PresentationConsentView {
  sessionId: string;
  state: PresentationState;
  holderId: string | null;
  verifier: VerifierConsentInfo;
  intendedUse: string[];
  privacyPolicyUri: string | null;
  registryWarnings: ConsentWarning[];
  minimization: MinimizationAssessment;
  queries: QueryConsentItem[];
  choiceGroups: CredentialChoiceGroup[];
  approvalMode: ApprovalMode;
}

export interface AuthorizationStartRequest {
  requestUri: string;
  holderId?: string;
}

export interface ConsentSubmission {
  sessionId: string;
  holderId: string;
  granted: boolean;
  selectedCredentialIds?: string[];
  reason?: string;
}

export interface SessionMetadata {
  sessionId: string;
  holderId: string | null;
  correlationId: string;
  createdAt: string;
  updatedAt: string;
  expiresAt: string;
  version: number;
}

export interface PresentationError {
  errorToken: string | null;
  code: string;
  message: string;
}

export interface PresentationContext {
  sessionMeta: SessionMetadata;
  state: PresentationState;
  error: PresentationError | null;
  dispatchOutcome: unknown;
  consentDecision: {
    granted: boolean;
    reason: string | null;
    selectedCredentialIds: string[];
  } | null;
}

export interface SessionEvent {
  sessionId: string;
  correlationId: string;
  timestamp: string;
  type: string;
  state: PresentationState;
  attributes: Record<string, string>;
}
