import { loadWalletState, saveWalletState } from "./storage";
import type { WalletInitResult, WalletUnitSummary } from "../../types/wallet";

export function walletInitFromUnit(unit: WalletUnitSummary): WalletInitResult {
  return {
    walletId: unit.walletId,
    state: unit.state,
    dpopBound: true,
    pidKeyBound: false,
  };
}

export function mergeWalletStateFromSummary(
  holderId: string,
  walletUnit: WalletUnitSummary | null | undefined,
): WalletInitResult | null {
  if (!walletUnit) {
    return loadWalletState(holderId);
  }
  const merged = walletInitFromUnit(walletUnit);
  saveWalletState(holderId, merged);
  return merged;
}
