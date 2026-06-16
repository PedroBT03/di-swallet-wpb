import type { Fido2AssertionPayload } from "../types/fido2";
import { bytesToBase64Url } from "./base64";

const HOLDER_ID_KEY = "wpi-dev.holderId";
const CREDENTIAL_ID_KEY = "wpi-dev.credentialId";
const REMEMBERED_SESSION_KEY = "wpi-dev.rememberedSession";

export interface HolderSession {
  holderId: string;
  credentialId: string;
}

export function loadSession(): HolderSession | null {
  const holderId = sessionStorage.getItem(HOLDER_ID_KEY);
  const credentialId = sessionStorage.getItem(CREDENTIAL_ID_KEY);
  if (!holderId || !credentialId) {
    return null;
  }
  return { holderId, credentialId };
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
  localStorage.setItem(REMEMBERED_SESSION_KEY, JSON.stringify(session));
}

/** Ends the active UI session but keeps this browser's remembered passkey mapping. */
export function clearSession(): void {
  sessionStorage.removeItem(HOLDER_ID_KEY);
  sessionStorage.removeItem(CREDENTIAL_ID_KEY);
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
