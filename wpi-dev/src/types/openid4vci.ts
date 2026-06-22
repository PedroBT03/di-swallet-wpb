export type AuthorizationFlowKind = "AUTHORIZATION_CODE" | "PRE_AUTHORIZED_CODE";

export type IssuanceCredentialFormat = "SD_JWT_VC" | "MSO_MDOC" | "UNKNOWN";

export type IssuanceState =
  | "OFFER_RECEIVED"
  | "OFFER_RESOLVED"
  | "AUTHORIZATION_PREPARED"
  | "AUTHORIZED"
  | "CREDENTIAL_REQUESTED"
  | "ISSUANCE_CONSENT_PENDING"
  | "CREDENTIAL_ISSUED"
  | "DEFERRED_PENDING"
  | "DEFERRED_ISSUED"
  | "NOTIFIED"
  | "FAILED"
  | "REJECTED"
  | "EXPIRED";

export type NotificationEvent = "CREDENTIAL_ACCEPTED" | "CREDENTIAL_DELETED" | "CREDENTIAL_FAILURE";

export interface IssuanceSessionMetadata {
  sessionId: string;
  holderId: string | null;
  correlationId: string;
  createdAt: string;
  updatedAt: string;
  expiresAt: string;
  version: number;
}

export interface IssuanceError {
  code: string;
  message: string;
  recoverable?: boolean;
}

export interface PreAuthorizedGrant {
  txCodeRequired: boolean;
  txCodeDescription?: string | null;
  txCodeLength?: number | null;
}

export interface ResolvedOffer {
  authorizationFlow: AuthorizationFlowKind;
  credentialIssuerId: string;
  credentialConfigurationIds: string[];
  preAuthorizedGrant?: PreAuthorizedGrant | null;
}

export interface PreparedAuthorization {
  adapterSessionId: string;
  authorizationCodeUrl: string;
  state: string;
  pkceUsed: boolean;
  parUsed: boolean;
  dpopRequested: boolean;
  wiaAttached: boolean;
}

export interface WiaStatusReference {
  listId: string;
  index: number;
  uri: string;
}

export interface WalletInstanceAttestation {
  jwt: string;
  popJwt: string;
  walletInstanceId: string;
  walletName: string;
  walletVersion: string;
  walletLink?: string | null;
  walletSolutionCertificationInformation: string;
  cnfJkt: string;
  clientStatus: WiaStatusReference;
  tokenExpiresAt: string;
  clientStatusExpiresAt: string;
  issuedAt: string;
  issuerScope?: string | null;
}

export interface WiaContext {
  state: string;
  attestation?: WalletInstanceAttestation | null;
  nonceMismatchRetries?: number;
  expiredRetries?: number;
  lastErrorCode?: string | null;
}

export interface KaStatusReference {
  listId: string;
  index: number;
  uri: string;
}

export interface KeyAttestation {
  jwt: string;
  keyId: string;
  keyStorage: string;
  certification: string;
  attestedJkt: string;
  status: KaStatusReference;
  tokenExpiresAt: string;
  statusExpiresAt: string;
  issuedAt: string;
  issuerScope?: string | null;
  x5c?: string[];
}

export interface KaContext {
  state: string;
  attestation?: KeyAttestation | null;
  lastErrorCode?: string | null;
}

export interface AuthorizedContext {
  adapterSessionId: string;
  accessTokenPresent: boolean;
  refreshTokenPresent: boolean;
  dpopUsed: boolean;
  cNoncePresent: boolean;
  authorizationServer?: string | null;
  accessTokenCnfJkt?: string | null;
  wiaCnfJkt?: string | null;
}

export interface IssuedCredentialSummary {
  credentialConfigurationId: string;
  format?: IssuanceCredentialFormat;
}

export interface IssuanceContext {
  sessionMeta: IssuanceSessionMetadata;
  state: IssuanceState;
  flow: AuthorizationFlowKind | null;
  credentialIssuerId: string | null;
  credentialConfigurationIds: string[];
  resolvedOffer: ResolvedOffer | null;
  preparedAuthorization: PreparedAuthorization | null;
  authorizedContext?: AuthorizedContext | null;
  wia?: WiaContext | null;
  ka?: KaContext | null;
  issuedCredentials: IssuedCredentialSummary[];
  deferredHandle: { transactionId: string } | null;
  error: IssuanceError | null;
}

export interface IssuerConsentInfo {
  credentialIssuerId: string | null;
  displayName: string | null;
}

export interface ClaimPreviewItem {
  name: string;
  value: string | null;
  previewAvailable: boolean;
}

export interface IssuanceConsentView {
  sessionId: string;
  state: IssuanceState;
  holderId: string | null;
  issuer: IssuerConsentInfo;
  credentialConfigurationId: string | null;
  format: IssuanceCredentialFormat;
  deviceBound: boolean;
  claimPreview: ClaimPreviewItem[];
}

export interface IssuanceConsentSubmission {
  sessionId: string;
  holderId: string;
  granted: boolean;
  reason?: string;
}

export interface OfferResolveRequest {
  offerUri: string;
  holderId?: string;
}

export interface IssuanceEvent {
  sessionId: string;
  correlationId: string;
  timestamp: string;
  type: string;
  state: IssuanceState;
  attributes: Record<string, string>;
}
