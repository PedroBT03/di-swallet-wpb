export interface VciDemoScenario {
  id: string;
  title: string;
  description: string;
  offerUri: string;
  defaultTxCode?: string;
  grant: "pre-authorized" | "authorization_code";
}

const ISSUER = "https://issuer.example";

/** Pre-filled credential_offer URIs for the WPB simulated issuer adapter. */
export const VCI_DEMO_SCENARIOS: VciDemoScenario[] = [
  {
    id: "pid-pre-authorized",
    title: "PID — pre-authorized (default)",
    description:
      "Simulated issuer with pre-authorized_code grant. Use tx_code 1234 when prompted.",
    grant: "pre-authorized",
    defaultTxCode: "1234",
    offerUri: `openid-credential-offer://credential_offer=${encodeURIComponent(
      JSON.stringify({
        credential_issuer: ISSUER,
        credential_configuration_ids: ["pid_jwt"],
        grants: {
          "urn:ietf:params:oauth:grant-type:pre-authorized_code": {
            tx_code: { length: 4 },
          },
        },
      }),
    )}`,
  },
  {
    id: "pid-auth-code",
    title: "PID — authorization code",
    description:
      "Full OAuth path: prepare → simulated code exchange → credential request → storage consent.",
    grant: "authorization_code",
    offerUri: `openid-credential-offer://credential_offer=${encodeURIComponent(
      JSON.stringify({
        credential_issuer: ISSUER,
        credential_configuration_ids: ["pid_jwt"],
      }),
    )}`,
  },
  {
    id: "academic-card",
    title: "Academic card (non device-bound)",
    description: "academic_card configuration without key attestation.",
    grant: "authorization_code",
    offerUri: `openid-credential-offer://credential_offer=${encodeURIComponent(
      JSON.stringify({
        credential_issuer: ISSUER,
        credential_configuration_ids: ["academic_card"],
      }),
    )}`,
  },
  {
    id: "pid-deferred",
    title: "PID — deferred issuance",
    description:
      "Requires wpb.openid4vci.simulator.always-defer=true on WPB. After Request credential, Continue polls until DEFERRED_ISSUED.",
    grant: "pre-authorized",
    defaultTxCode: "1234",
    offerUri: `openid-credential-offer://credential_offer=${encodeURIComponent(
      JSON.stringify({
        credential_issuer: ISSUER,
        credential_configuration_ids: ["pid_jwt"],
        grants: {
          "urn:ietf:params:oauth:grant-type:pre-authorized_code": {
            tx_code: { length: 4 },
          },
        },
      }),
    )}`,
  },
];
