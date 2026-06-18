import type { Ts10ClaimInfo, Ts10Transaction } from "./transactionLog";

export interface EligiblePresentation {
  presentationTransactionId: string;
  rpIdentifier: string | null;
  rpName: string | null;
  presentationTime: string;
  presentedClaims: Ts10ClaimInfo[];
  hasStoredDeletionContacts: boolean;
}

export interface PrivacyAction {
  channel: string;
  uri: string;
}

export interface DataDeletionInitiateRequest {
  holderId: string;
  presentationTransactionId: string;
  claimsToDelete?: Ts10ClaimInfo[];
  deleteAllPresented?: boolean;
  consentRegistryLookup?: boolean;
}

export interface DataDeletionInitiateResponse {
  transactionId: string | null;
  sourcePresentationTransactionId: string;
  rpIdentifier: string | null;
  rpName: string | null;
  availableActions: PrivacyAction[];
  registryLookupPerformed: boolean;
  userNotice: string | null;
}

export interface EligibleDpaReportPresentation {
  presentationTransactionId: string;
  transactionResult: string;
  rpIdentifier: string | null;
  rpName: string | null;
  presentationTime: string;
  hasStoredDpaContacts: boolean;
}

export interface DpaReportInitiateRequest {
  holderId: string;
  presentationTransactionId: string;
  consentRegistryLookup?: boolean;
}

export interface DpaReportInitiateResponse {
  transactionId: string | null;
  sourcePresentationTransactionId: string;
  rpIdentifier: string | null;
  rpName: string | null;
  dpaName: string | null;
  dpaCountry: string | null;
  dnsName: string | null;
  dnsNameSource: string | null;
  availableActions: PrivacyAction[];
  registryLookupPerformed: boolean;
  userNotice: string | null;
  substantiationDocument: Ts10Transaction;
}
