import { apiFetch } from "./client";

export interface WpbOperationalInfo {
  operational?: {
    demoMode?: {
      openid4vp?: boolean;
      openid4vci?: boolean;
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
