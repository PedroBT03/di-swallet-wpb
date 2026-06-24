import type {
  WalletInitRequest,
  WalletInitResult,
  WalletKeyRecord,
  WalletUnitSummary,
} from "../../types/wallet";

const exchangeKey = (holderId: string) => `wpi-dev.walletInitExchange.${holderId}`;

/** Payloads captured during POST /wallet/init for demo / developer inspection. */
export interface WalletInitExchange {
  completedAt: string;
  endpoint: string;
  request: WalletInitRequest;
  response: WalletInitResult;
  postSync?: {
    completedAt: string;
    walletUnit: WalletUnitSummary | null;
    key: WalletKeyRecord | null;
  };
}

export function loadWalletInitExchange(holderId: string): WalletInitExchange | null {
  const raw = localStorage.getItem(exchangeKey(holderId));
  if (!raw) {
    return null;
  }
  try {
    return JSON.parse(raw) as WalletInitExchange;
  } catch {
    return null;
  }
}

export function saveWalletInitExchange(holderId: string, exchange: WalletInitExchange): void {
  localStorage.setItem(exchangeKey(holderId), JSON.stringify(exchange));
}

export function clearWalletInitExchange(holderId: string): void {
  localStorage.removeItem(exchangeKey(holderId));
}

/** Pretty-print request with device_pub JWK parsed when possible. */
export function formatInitRequestForDev(request: WalletInitRequest): Record<string, unknown> {
  let devicePubJwk: unknown = request.devicePubJwk;
  try {
    devicePubJwk = JSON.parse(request.devicePubJwk) as unknown;
  } catch {
    // keep raw string
  }
  return {
    holderId: request.holderId,
    platform: request.platform,
    devicePubJwk,
    userDeviceId: request.userDeviceId,
  };
}

/** Decode JWT header and payload for developer panels (no signature verification). */
export function decodeJwtParts(jwt: string): { header: unknown; payload: unknown } | null {
  const segments = jwt.split(".");
  if (segments.length < 2) {
    return null;
  }
  const decodeSegment = (segment: string): unknown => {
    const normalized = segment.replace(/-/g, "+").replace(/_/g, "/");
    const padded = normalized.padEnd(normalized.length + ((4 - (normalized.length % 4)) % 4), "=");
    return JSON.parse(atob(padded)) as unknown;
  };
  try {
    return {
      header: decodeSegment(segments[0]),
      payload: decodeSegment(segments[1]),
    };
  } catch {
    return null;
  }
}
