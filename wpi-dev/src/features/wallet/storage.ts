import { formatCredentialTypeLabel } from "../../utils/credentialType";
import type { CredentialSummary, WalletCredentialRecord, WalletInitResult } from "../../types/wallet";

const walletStateKey = (holderId: string) => `wpi-dev.walletState.${holderId}`;

export function loadWalletState(holderId: string): WalletInitResult | null {
  const raw = localStorage.getItem(walletStateKey(holderId));
  if (!raw) return null;
  try {
    return JSON.parse(raw) as WalletInitResult;
  } catch {
    return null;
  }
}

export function saveWalletState(holderId: string, state: WalletInitResult): void {
  localStorage.setItem(walletStateKey(holderId), JSON.stringify(state));
}

export function summarizeCredential(credential: WalletCredentialRecord): CredentialSummary {
  const preview =
    credential.encodedData.length > 48
      ? `${credential.encodedData.slice(0, 48)}…`
      : credential.encodedData;
  return {
    id: credential.id,
    credentialType: formatCredentialTypeLabel(credential.credentialType),
    issuedAt: credential.issuedAt,
    revocationState: credential.revocationState,
    deviceBound: credential.deviceBound,
    statusListId: credential.statusListId,
    statusListIndex: credential.statusListIndex,
    encodedPreview: preview,
  };
}

/** Newest credentials first (by issuedAt, then id as tiebreaker). */
export function sortCredentialsByIssuedAt(
  credentials: CredentialSummary[],
): CredentialSummary[] {
  return [...credentials].sort((left, right) => {
    const leftTime = Date.parse(left.issuedAt);
    const rightTime = Date.parse(right.issuedAt);
    const leftMs = Number.isNaN(leftTime) ? 0 : leftTime;
    const rightMs = Number.isNaN(rightTime) ? 0 : rightTime;
    if (rightMs !== leftMs) {
      return rightMs - leftMs;
    }
    return right.id - left.id;
  });
}
