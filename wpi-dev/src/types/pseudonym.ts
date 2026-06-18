export type PseudonymStatus = "PENDING" | "REGISTERED";

export interface PseudonymView {
  id: string;
  holderId: string;
  rpId: string;
  alias: string | null;
  status: PseudonymStatus;
  credentialId: string | null;
  createdAt: string;
  lastUsedAt: string | null;
}

export interface CreatePseudonymRequest {
  holderId: string;
  rpId: string;
  alias?: string;
}

export interface RegistrationOptionsResponse {
  challenge: string;
  rpId: string;
  rpName: string;
  userHandle: string;
  timeout: number;
  pubKeyCredParams: Array<Record<string, unknown>>;
}

export interface RegistrationFinishResponse {
  credentialId: string;
  attestationObject: string;
  clientDataJSON: string;
  publicKeyCose: string;
}
