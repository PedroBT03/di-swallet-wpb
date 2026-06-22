import {
  buildAcademicCardOfferUri,
  buildCredentialOfferUri,
  DEFAULT_ISSUANCE_GRANT,
  type GrantType,
} from "../features/issue/offerBuilder";

export interface VciDemoScenario {
  id: string;
  title: string;
  description: string;
  offerUri: string;
  grant: GrantType;
  documentId: string;
  issuanceLabel: string;
  /** Shown only under technical demos */
  technical?: boolean;
}

/** Pre-filled credential offers for the WPB simulated issuer. */
export const VCI_DEMO_SCENARIOS: VciDemoScenario[] = [
  {
    id: "pid-auth-code",
    title: "Issue PID (recommended)",
    description:
      "Resolve offer, CMD identity (simulated), request credential, approve storage.",
    grant: DEFAULT_ISSUANCE_GRANT,
    documentId: "pid",
    issuanceLabel: "Identity (PID)",
    offerUri: buildCredentialOfferUri("pid_jwt", DEFAULT_ISSUANCE_GRANT),
  },
  {
    id: "mdl-auth-code",
    title: "Issue driving licence (mDL)",
    description:
      "ISO 18013-5 mdoc issuance via the simulator. Proximity flows are not in scope; use this to test storage and data format.",
    grant: DEFAULT_ISSUANCE_GRANT,
    documentId: "mdl",
    issuanceLabel: "Driving licence (mDL)",
    offerUri: buildCredentialOfferUri("mdoc_mdl", DEFAULT_ISSUANCE_GRANT),
  },
  {
    id: "pid-deferred",
    title: "Issue PID (deferred)",
    description:
      "Requires wpb.openid4vci.simulator.always-defer=true on WPB. Continue polls until the credential is ready.",
    grant: DEFAULT_ISSUANCE_GRANT,
    documentId: "pid",
    issuanceLabel: "Identity (PID, deferred)",
    offerUri: buildCredentialOfferUri("pid_jwt", DEFAULT_ISSUANCE_GRANT),
  },
  {
    id: "pid-pre-authorized",
    title: "Issue PID (pre-authorized, technical)",
    description: "Adapter-only grant without CMD step. Not used in production PID issuance.",
    grant: "pre-authorized",
    documentId: "pid",
    issuanceLabel: "Identity (PID, pre-authorized)",
    technical: true,
    offerUri: buildCredentialOfferUri("pid_jwt", "pre-authorized"),
  },
  {
    id: "academic-card",
    title: "Academic card (technical)",
    description: "Non device-bound academic_card configuration for adapter testing only.",
    grant: "authorization_code",
    documentId: "pid",
    issuanceLabel: "Academic card",
    technical: true,
    offerUri: buildAcademicCardOfferUri(),
  },
];

export const VCI_QUICK_DEMOS = VCI_DEMO_SCENARIOS.filter((scenario) => !scenario.technical);
export const VCI_TECHNICAL_DEMOS = VCI_DEMO_SCENARIOS.filter((scenario) => scenario.technical);
