import { apiFetch } from "./client";

export interface WpbOperationalInfo {
  operational?: {
    demoMode?: {
      openid4vp?: boolean;
      openid4vci?: boolean;
    };
    transactionLog?: {
      dekMode?: string;
    };
  };
}

export function fetchWpbOperationalInfo(): Promise<WpbOperationalInfo> {
  return apiFetch<WpbOperationalInfo>("/actuator/info", { raw: true });
}

/** Returns demo-mode flag when present; null when the backend did not report it. */
export function parseOpenId4VpDemoMode(info: WpbOperationalInfo): boolean | null {
  const value = info.operational?.demoMode?.openid4vp;
  return typeof value === "boolean" ? value : null;
}

/** Returns OID4VCI demo-mode flag when present; null when the backend did not report it. */
export function parseOpenId4VciDemoMode(info: WpbOperationalInfo): boolean | null {
  const value = info.operational?.demoMode?.openid4vci;
  return typeof value === "boolean" ? value : null;
}

/** Returns transaction log DEK mode when reported by actuator /info. */
export function parseTransactionLogDekMode(
  info: WpbOperationalInfo,
): "server" | "holder" | null {
  const raw = info.operational?.transactionLog?.dekMode?.toLowerCase();
  if (raw === "server" || raw === "holder") {
    return raw;
  }
  return null;
}
