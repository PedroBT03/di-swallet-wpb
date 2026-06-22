export interface VpDemoScenario {
  id: string;
  /** Short title shown on scenario cards */
  title: string;
  /** Plain-language explanation of what the verifier asks for */
  description: string;
  /** Who is asking (demo verifier label) */
  verifierLabel: string;
  /** Human-readable summary of disclosed attributes */
  sharesLabel: string;
  /** Claim paths included in the VP (for success summary) */
  requestedClaims: string[];
  requestUri: string;
  documentType: "pid" | "mdl";
  requiresPid?: boolean;
  requiresMdl?: boolean;
}

import {
  VERIFIER_EMULATOR_BASE,
  type PresentDocumentType,
} from "../features/present/pidClaims";

/** User-facing verifier demos that exercise selective disclosure (DCQL claim paths). */
export const VP_PRESENT_SCENARIOS: VpDemoScenario[] = [
  {
    id: "pid-given-name",
    title: "Shop asks for your first name",
    description:
      "A verifier only needs your given name to personalise a receipt. Your family name, birth date, full address, and other PID fields stay in the wallet.",
    verifierLabel: "Demo shop (local emulator)",
    sharesLabel: "Given name only",
    requestedClaims: ["given_name"],
    requestUri: `${VERIFIER_EMULATOR_BASE}/request/conformance/simple_claim.json`,
    documentType: "pid",
    requiresPid: true,
  },
  {
    id: "pid-address-locality",
    title: "Service asks for your city",
    description:
      "Nested selective disclosure: only the city (address.locality) is revealed, not your street, postal code, or identity attributes.",
    verifierLabel: "Demo shop (local emulator)",
    sharesLabel: "City (address.locality)",
    requestedClaims: ["address.locality"],
    requestUri: `${VERIFIER_EMULATOR_BASE}/request/conformance/nested_claim.json`,
    documentType: "pid",
    requiresPid: true,
  },
  {
    id: "mdl-driving-categories",
    title: "Rental asks for licence categories",
    description:
      "A car rental only needs your driving categories (e.g. B). Name, birth date, and other mDL fields stay in the wallet.",
    verifierLabel: "Demo rental (local emulator)",
    sharesLabel: "Driving categories",
    requestedClaims: ["driving_privileges"],
    requestUri: `${VERIFIER_EMULATOR_BASE}/request/conformance/mdl_driving_privileges.json`,
    documentType: "mdl",
    requiresMdl: true,
  },
  {
    id: "mdl-given-name",
    title: "Counter asks for your first name",
    description:
      "Selective disclosure from your mobile driving licence: only given_name is shared from the mDL document.",
    verifierLabel: "Demo counter (local emulator)",
    sharesLabel: "Given name (mDL)",
    requestedClaims: ["given_name"],
    requestUri: `${VERIFIER_EMULATOR_BASE}/request/conformance/mdl_given_name.json`,
    documentType: "mdl",
    requiresMdl: true,
  },
];

/** @deprecated Use VP_PRESENT_SCENARIOS. Kept for scenario docs and advanced tooling. */
export const VP_DEMO_SCENARIOS = VP_PRESENT_SCENARIOS;

export function scenariosForDocument(documentType: PresentDocumentType): VpDemoScenario[] {
  return VP_PRESENT_SCENARIOS.filter((scenario) => scenario.documentType === documentType);
}
