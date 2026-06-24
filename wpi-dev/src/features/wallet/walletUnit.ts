import { clearWalletState, loadWalletState, saveWalletState } from "./storage";
import type { WalletInitResult, WalletUnitSummary } from "../../types/wallet";

export function walletInitFromUnit(unit: WalletUnitSummary): WalletInitResult {
  return {
    walletId: unit.walletId,
    state: unit.state,
    dpopBound: true,
  };
}

export function mergeWalletStateFromSummary(
  holderId: string,
  walletUnit: WalletUnitSummary | null | undefined,
): WalletInitResult | null {
  if (!walletUnit) {
    clearWalletState(holderId);
    return null;
  }
  const previous = loadWalletState(holderId);
  const merged: WalletInitResult = {
    ...walletInitFromUnit(walletUnit),
    wia: previous?.wia,
    ka: previous?.ka,
  };
  saveWalletState(holderId, merged);
  return merged;
}
