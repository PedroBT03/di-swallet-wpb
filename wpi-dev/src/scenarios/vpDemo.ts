export interface VpDemoScenario {
  id: string;
  title: string;
  description: string;
  requestUri: string;
  requiresPid?: boolean;
}

const VERIFIER_BASE = "http://localhost:8081";

/** Pre-filled request_uri values for the local verifier emulator (port 8081). */
export const VP_DEMO_SCENARIOS: VpDemoScenario[] = [
  {
    id: "pid-given-name",
    title: "PID — given_name",
    description: "DCQL selective disclosure of given_name from demo PID (issue on Wallet first).",
    requestUri: `${VERIFIER_BASE}/request/conformance/simple_claim.json`,
    requiresPid: true,
  },
  {
    id: "pid-address-locality",
    title: "PID — address.locality",
    description: "Nested claim path from demo PID.",
    requestUri: `${VERIFIER_BASE}/request/conformance/nested_claim.json`,
    requiresPid: true,
  },
  {
    id: "direct-post-legacy",
    title: "Legacy direct_post",
    description: "Minimal authorization request (may not match wallet credentials).",
    requestUri: `${VERIFIER_BASE}/request/direct_post.json`,
  },
  {
    id: "redirect-query",
    title: "Redirect (query)",
    description: "Response mode query with redirect return to emulator.",
    requestUri: `${VERIFIER_BASE}/request/redirect_query.json`,
  },
  {
    id: "redirect-fragment",
    title: "Redirect (fragment)",
    description: "Response mode fragment with redirect return to emulator.",
    requestUri: `${VERIFIER_BASE}/request/redirect_fragment.json`,
  },
];
