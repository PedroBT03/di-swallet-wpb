export type PresentDocumentType = "pid" | "mdl";

export interface PresentClaimOption {
  id: string;
  label: string;
  group: string;
}

export interface PresentDocument {
  id: PresentDocumentType;
  label: string;
  description: string;
}

export const PRESENT_DOCUMENTS: PresentDocument[] = [
  {
    id: "pid",
    label: "Identity (PID)",
    description: "SD-JWT selective disclosure",
  },
  {
    id: "mdl",
    label: "Driving licence (mDL)",
    description: "ISO 18013-5 mdoc",
  },
];

/** Claim paths available in the demo PID (see DemoAttestationClaims / MockIssuerService). */
export const PID_CLAIM_OPTIONS: PresentClaimOption[] = [
  { id: "given_name", label: "Given name", group: "Mandatory attributes" },
  { id: "family_name", label: "Family name", group: "Mandatory attributes" },
  { id: "birthdate", label: "Date of birth", group: "Mandatory attributes" },
  { id: "place_of_birth", label: "Place of birth", group: "Mandatory attributes" },
  { id: "nationalities", label: "Nationality", group: "Mandatory attributes" },
  { id: "date_of_expiry", label: "Expiry date", group: "Mandatory metadata" },
  { id: "issuing_authority", label: "Issuing authority", group: "Mandatory metadata" },
  { id: "issuing_country", label: "Issuing country", group: "Mandatory metadata" },
  { id: "address.locality", label: "City", group: "Demo optional" },
  { id: "address.country", label: "Country", group: "Demo optional" },
];

/** mDL namespace claims issued by the simulator (org.iso.18013.5.1). */
export const MDL_CLAIM_OPTIONS: PresentClaimOption[] = [
  { id: "given_name", label: "Given name", group: "mDL attributes" },
  { id: "family_name", label: "Family name", group: "mDL attributes" },
  { id: "birth_date", label: "Date of birth", group: "mDL attributes" },
  { id: "driving_privileges", label: "Driving categories", group: "mDL attributes" },
  { id: "issuing_country", label: "Issuing country", group: "mDL attributes" },
];

export const VERIFIER_EMULATOR_BASE = "http://localhost:8081";

/** Static conformance fixtures served by the verifier emulator (no dynamic route required). */
const PID_SINGLE_CLAIM_REQUESTS: Record<string, string> = {
  given_name: "/request/conformance/simple_claim.json",
  family_name: "/request/conformance/family_name.json",
  birthdate: "/request/conformance/birthdate.json",
  nationalities: "/request/conformance/nationalities.json",
  "address.locality": "/request/conformance/nested_claim.json",
  "address.country": "/request/conformance/address_country.json",
};

const MDL_SINGLE_CLAIM_REQUESTS: Record<string, string> = {
  given_name: "/request/conformance/mdl_given_name.json",
  family_name: "/request/conformance/mdl_family_name.json",
  birth_date: "/request/conformance/mdl_birth_date.json",
  driving_privileges: "/request/conformance/mdl_driving_privileges.json",
  issuing_country: "/request/conformance/mdl_issuing_country.json",
};

function normalizeClaimIds(claimIds: string[]): string[] {
  return [...new Set(claimIds.map((id) => id.trim()).filter(Boolean))].sort();
}

export function claimOptionsForDocument(documentType: PresentDocumentType): PresentClaimOption[] {
  return documentType === "mdl" ? MDL_CLAIM_OPTIONS : PID_CLAIM_OPTIONS;
}

export function defaultClaimsForDocument(documentType: PresentDocumentType): string[] {
  return documentType === "mdl" ? ["driving_privileges"] : ["given_name"];
}

/**
 * Resolves a verifier request_uri for the selected claim paths and document type.
 * Single-field selections use static JSON fixtures; multi-field uses the emulator build route.
 */
export function buildCustomVerifierRequestUri(
  claimIds: string[],
  documentType: PresentDocumentType = "pid",
): string {
  const normalized = normalizeClaimIds(claimIds);
  if (documentType === "mdl") {
    if (normalized.length === 1) {
      const staticPath = MDL_SINGLE_CLAIM_REQUESTS[normalized[0]];
      if (staticPath) {
        return `${VERIFIER_EMULATOR_BASE}${staticPath}`;
      }
    }
    const claims = normalized.join(",");
    return `${VERIFIER_EMULATOR_BASE}/request/build-mdl/${claims}.json`;
  }

  if (normalized.length === 1) {
    const staticPath = PID_SINGLE_CLAIM_REQUESTS[normalized[0]];
    if (staticPath) {
      return `${VERIFIER_EMULATOR_BASE}${staticPath}`;
    }
  }
  const claims = normalized.join(",");
  return `${VERIFIER_EMULATOR_BASE}/request/build/${claims}.json`;
}

export function formatClaimsLabel(
  claimIds: string[],
  documentType: PresentDocumentType = "pid",
): string {
  if (claimIds.length === 0) {
    return "";
  }
  const options = claimOptionsForDocument(documentType);
  const labels = claimIds.map((id) => {
    const option = options.find((entry) => entry.id === id);
    return option?.label ?? id;
  });
  if (labels.length <= 2) {
    return labels.join(" + ");
  }
  return `${labels.slice(0, 2).join(", ")} + ${labels.length - 2} more`;
}

export function groupClaimOptions(
  options: PresentClaimOption[],
): Record<string, PresentClaimOption[]> {
  return options.reduce<Record<string, PresentClaimOption[]>>((groups, option) => {
    if (!groups[option.group]) {
      groups[option.group] = [];
    }
    groups[option.group].push(option);
    return groups;
  }, {});
}

/** @deprecated Use PresentClaimOption */
export type PidClaimOption = PresentClaimOption;
