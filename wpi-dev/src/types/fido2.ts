export interface Fido2AssertionPayload {
  userId: string;
  id: string;
  clientDataJSON: string;
  authenticatorData: string;
  signature: string;
}

export interface AuthChallengeResponse {
  userId: string;
  challenge: string;
  info?: string;
}
