export interface TransactionLogSummary {
  transactionId: string;
  transactionType: string;
  transactionResult: string;
  occurredAt: string;
  deletedByUser: boolean;
}

export interface LogClaimInfo {
  credentialIdentifier: string;
  claims: string[];
}

export interface LogTransactionDetail {
  transactionIdentifier: string;
  time: string;
  transactionType: string;
  transactionResult: string;
  presentation?: Record<string, unknown>;
  credentialIssuance?: Record<string, unknown>;
  credentialDeletion?: Record<string, unknown>;
  signingSealing?: Record<string, unknown>;
  dataDeletionRequest?: Record<string, unknown>;
  dpaReport?: Record<string, unknown>;
  otherTransaction?: Record<string, unknown>;
}

export interface TransactionExportRequest {
  holderId: string;
  transactionIds?: string[];
  password: string;
}
