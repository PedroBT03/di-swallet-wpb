export type TrustMarkActionType = "CERTIFIED_WALLETS_LIST" | "WALLET_SOLUTION_INFO";

export interface TrustMarkInformation {
  trustMarkResourceUrl: string;
  listOfCertifiedWalletsUrl: string;
  walletSolutionInfoPageUrl: string;
  walletSolutionId?: string | null;
}

export interface TrustMarkResourceView {
  imageUrl: string | null;
  imageName: string | null;
  localizedText: string | null;
  language: string;
  availableLanguages: string[];
}

export interface TrustMarkAction {
  type: TrustMarkActionType;
  uri: string;
}

export interface TrustMarkView {
  enabled: boolean;
  walletSolutionId?: string | null;
  information?: TrustMarkInformation | null;
  resource?: TrustMarkResourceView | null;
  actions: TrustMarkAction[];
  fetchedAt?: string | null;
  cacheSource?: string | null;
  warnings: string[];
  userNotice?: string | null;
}
