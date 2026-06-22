import type { Fido2AssertionPayload } from "../types/fido2";
import { bytesToBase64Url } from "./base64";

const HOLDER_ID_KEY = "wpi-dev.holderId";
const CREDENTIAL_ID_KEY = "wpi-dev.credentialId";
const USER_DEVICE_ID_KEY = "wpi-dev.userDeviceId";
const REMEMBERED_SESSION_KEY = "wpi-dev.rememberedSession";
const HOLDER_SESSION_TOKEN_KEY = "wpi-dev.holderSessionToken";
const HOLDER_SESSION_EXPIRES_KEY = "wpi-dev.holderSessionExpires";

export interface HolderSession {
  holderId: string;
  credentialId: string;
  /** COSE public key from passkey registration. Used to re-bind WPB after DB resets. */
  publicKeyBase64?: string;
  userDeviceId?: number;
}

export function loadSession(): HolderSession | null {
  const holderId = sessionStorage.getItem(HOLDER_ID_KEY);
  const credentialId = sessionStorage.getItem(CREDENTIAL_ID_KEY);
  const userDeviceIdRaw = sessionStorage.getItem(USER_DEVICE_ID_KEY);
  const remembered = loadRememberedSession();
  if (!holderId || !credentialId) {
    return null;
  }
  const userDeviceId = userDeviceIdRaw ? Number(userDeviceIdRaw) : undefined;
  return {
    holderId,
    credentialId,
    publicKeyBase64: remembered?.holderId === holderId ? remembered.publicKeyBase64 : undefined,
    userDeviceId: Number.isFinite(userDeviceId) ? userDeviceId : undefined,
  };
}

/** Last successful holder on this browser (survives sign-out). */
export function loadRememberedSession(): HolderSession | null {
  const raw = localStorage.getItem(REMEMBERED_SESSION_KEY);
  if (!raw) {
    return null;
  }
  try {
    const parsed = JSON.parse(raw) as HolderSession;
    if (parsed.holderId && parsed.credentialId) {
      return parsed;
    }
  } catch {
    /* ignore */
  }
  return null;
}

export function saveSession(session: HolderSession): void {
  sessionStorage.setItem(HOLDER_ID_KEY, session.holderId);
  sessionStorage.setItem(CREDENTIAL_ID_KEY, session.credentialId);
  if (session.userDeviceId != null) {
    sessionStorage.setItem(USER_DEVICE_ID_KEY, String(session.userDeviceId));
  } else {
    sessionStorage.removeItem(USER_DEVICE_ID_KEY);
  }
  localStorage.setItem(REMEMBERED_SESSION_KEY, JSON.stringify(session));
}

/** Ends the active UI session but keeps this browser's remembered passkey mapping. */
export function clearSession(): void {
  sessionStorage.removeItem(HOLDER_ID_KEY);
  sessionStorage.removeItem(CREDENTIAL_ID_KEY);
  sessionStorage.removeItem(USER_DEVICE_ID_KEY);
  clearHolderSessionToken();
}

export function saveHolderSessionToken(sessionToken: string, expiresAt: string): void {
  sessionStorage.setItem(HOLDER_SESSION_TOKEN_KEY, sessionToken);
  sessionStorage.setItem(HOLDER_SESSION_EXPIRES_KEY, expiresAt);
}

export function loadHolderSessionToken(): string | null {
  const token = sessionStorage.getItem(HOLDER_SESSION_TOKEN_KEY);
  const expiresAt = sessionStorage.getItem(HOLDER_SESSION_EXPIRES_KEY);
  if (!token || !expiresAt) {
    return null;
  }
  const expiresMs = Date.parse(expiresAt);
  if (!Number.isFinite(expiresMs) || Date.now() >= expiresMs) {
    clearHolderSessionToken();
    return null;
  }
  return token;
}

export function clearHolderSessionToken(): void {
  sessionStorage.removeItem(HOLDER_SESSION_TOKEN_KEY);
  sessionStorage.removeItem(HOLDER_SESSION_EXPIRES_KEY);
}

/** Builds headers with a holder session token for dashboard/read APIs (WIAM_15). */
export function withSessionAuth(headers: Headers = new Headers()): Headers {
  const token = loadHolderSessionToken();
  if (token) {
    headers.set("X-Wallet-Session", token);
  }
  return headers;
}

export function forgetRememberedSession(): void {
  localStorage.removeItem(REMEMBERED_SESSION_KEY);
  clearSession();
}

export function encodeAssertionHeader(assertion: Fido2AssertionPayload): string {
  const json = JSON.stringify(assertion);
  const encoded = bytesToBase64Url(new TextEncoder().encode(json));
  return `fido2-assertion:${encoded}`;
}

/** Builds headers with a verified WebAuthn assertion for protected WPB calls. */
export function withAuth(assertion: Fido2AssertionPayload): Headers {
  const headers = new Headers();
  headers.set("X-Wallet-Authorization", encodeAssertionHeader(assertion));
  return headers;
}
