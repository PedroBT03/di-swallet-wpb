export interface Fido2AssertionPayload {
  userId: string;
  id: string;
  clientDataJSON: string;
  authenticatorData: string;
  signature: string;
}

import type { PublicKeyCredentialRequestOptionsJSON } from "@simplewebauthn/types";

export interface AuthChallengeResponse {
  userId: string;
  challenge: string;
  /** Server-issued WebAuthn options. Must be passed unchanged to startAuthentication. */
  publicKeyCredentialRequestOptions: PublicKeyCredentialRequestOptionsJSON;
  info?: string;
}
