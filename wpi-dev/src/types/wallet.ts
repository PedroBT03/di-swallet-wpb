export interface UserDeviceRecord {
  id: number;
  userId: string;
  credentialId: string;
  userHandle: string;
  signatureCount: number;
  registeredAt: string;
}

export interface WalletInitRequest {
  holderId: string;
  platform: string;
  devicePubJwk: string;
  pidPubJwk?: string;
  userDeviceId?: number;
}

export interface WalletInitResult {
  walletId: string;
  state: string;
  dpopBound: boolean;
  pidKeyBound: boolean;
}

export interface WalletKeyRecord {
  id?: number;
  userId: string;
  keyAlias: string;
  publicKeyBase64: string;
  revocationIndex: number;
  createdAt?: string;
}

export interface WalletCredentialRecord {
  id: number;
  userId: string;
  credentialType: string;
  encodedData: string;
  issuedAt: string;
  statusListId?: string | null;
  statusListIndex?: number | null;
  issuerStatusUri?: string | null;
  issuerStatusIndex?: number | null;
  revocationState: string;
  deviceBound: boolean;
}

export interface CredentialSummary {
  id: number;
  credentialType: string;
  issuedAt: string;
  revocationState: string;
  deviceBound: boolean;
  statusListId?: string | null;
  statusListIndex?: number | null;
  encodedPreview: string;
}

export interface WalletUnitSummary {
  walletId: string;
  state: string;
}

export interface WalletSummaryResponse {
  key: WalletKeyRecord | null;
  credentials: CredentialSummary[];
  walletUnit: WalletUnitSummary | null;
}

export interface SignResult {
  userId: string;
  signature: string;
  algorithm: string;
}

export interface RevokeKeyResult {
  status: string;
  index: string;
}
