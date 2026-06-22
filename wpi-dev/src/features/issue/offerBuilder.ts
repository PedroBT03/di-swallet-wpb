export const SIMULATED_ISSUER = "https://issuer.example";

export type GrantType = "pre-authorized" | "authorization_code";

export interface IssuableDocument {
  id: string;
  label: string;
  description: string;
  configurationId: string;
  labStatus: "available" | "unavailable";
  unavailableNote?: string;
  /** Extra context when the document is available but has lab limitations. */
  labNote?: string;
}

export const ISSUABLE_DOCUMENTS: IssuableDocument[] = [
  {
    id: "pid",
    label: "Identity (PID)",
    description:
      "EU Person Identification Data with mandatory CIR attributes and metadata (SD-JWT).",
    configurationId: "pid_jwt",
    labStatus: "available",
  },
  {
    id: "mdl",
    label: "Driving licence (mDL)",
    description: "Mobile driving licence attestation (ISO 18013-5 mdoc format).",
    configurationId: "mdoc_mdl",
    labStatus: "available",
    labNote:
      "Proximity presentation (NFC/offline) is not supported by design. Issuance leverages the mDL data format and validates credential storage and wallet synchronization mechanisms for testing purposes.",
  },
];

/** Production PID issuance uses authorization_code + CMD citizen login. */
export const DEFAULT_ISSUANCE_GRANT: GrantType = "authorization_code";

export function availableDocuments(): IssuableDocument[] {
  return ISSUABLE_DOCUMENTS;
}

export function getDocument(id: string): IssuableDocument | undefined {
  return ISSUABLE_DOCUMENTS.find((document) => document.id === id);
}

export function availableGrantTypes(configurationId: string): GrantType[] {
  if (configurationId === "academic_card") {
    return ["authorization_code"];
  }
  return ["pre-authorized", "authorization_code"];
}

export function buildCredentialOfferUri(
  configurationId: string,
  grant: GrantType = DEFAULT_ISSUANCE_GRANT,
): string {
  const offer: Record<string, unknown> = {
    credential_issuer: SIMULATED_ISSUER,
    credential_configuration_ids: [configurationId],
  };
  if (grant === "pre-authorized") {
    offer.grants = {
      "urn:ietf:params:oauth:grant-type:pre-authorized_code": {},
    };
  }
  return `openid-credential-offer://credential_offer=${encodeURIComponent(JSON.stringify(offer))}`;
}

export function formatIssuanceLabel(documentId: string): string {
  const document = getDocument(documentId);
  return document?.label ?? documentId;
}

/** Technical non-PID scenario for conformance (device-bound vs not). */
export function buildAcademicCardOfferUri(): string {
  return buildCredentialOfferUri("academic_card", "authorization_code");
}
