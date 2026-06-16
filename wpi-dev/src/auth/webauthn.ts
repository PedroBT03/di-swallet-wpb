import { startAuthentication, startRegistration } from "@simplewebauthn/browser";
import type {
  AuthenticationResponseJSON,
  PublicKeyCredentialCreationOptionsJSON,
  PublicKeyCredentialRequestOptionsJSON,
} from "@simplewebauthn/types";
import { decode } from "cbor-x";
import type { Fido2AssertionPayload } from "../types/fido2";
import { base64UrlToBytes, bytesToBase64Url, stringToBase64Url } from "./base64";

export const walletRpId = import.meta.env.VITE_WALLET_RP_ID ?? "localhost";
export const walletRpName =
  import.meta.env.VITE_WALLET_RP_NAME ?? "DI-Swallet Wallet Provider";

function randomChallenge(): string {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  return bytesToBase64Url(bytes);
}

function extractCosePublicKey(attestationObjectB64: string): string {
  const attestation = decode(base64UrlToBytes(attestationObjectB64)) as { authData: Uint8Array };
  const authData = attestation.authData;
  if (!authData || authData.length < 37) {
    throw new Error("Registration response did not include authenticator data.");
  }

  const attestedCredentialDataIncluded = (authData[32] & 0x40) !== 0;
  if (!attestedCredentialDataIncluded) {
    throw new Error("Registration response did not include an attested credential.");
  }

  let offset = 37;
  offset += 16; // AAGUID
  const credentialIdLength = (authData[offset] << 8) | authData[offset + 1];
  offset += 2 + credentialIdLength;
  const coseKey = authData.slice(offset);
  if (coseKey.length === 0 || coseKey[0] !== 0xa5) {
    throw new Error("Could not extract a COSE public key from the passkey.");
  }
  return bytesToBase64Url(coseKey);
}

export function formatWebAuthnError(error: unknown): string {
  if (error instanceof DOMException) {
    if (error.name === "NotAllowedError") {
      return "Passkey prompt was cancelled, dismissed, or timed out.";
    }
    if (error.name === "SecurityError") {
      return "WebAuthn is not allowed from this origin. Ensure http://localhost:5173 is in wallet.origins.";
    }
    if (error.name === "InvalidStateError") {
      return "A passkey for this holder already exists on this device. Use re-register or another holder id.";
    }
    return error.message;
  }
  if (error instanceof Error) {
    return error.message;
  }
  return "WebAuthn operation failed.";
}

export interface RegisterPasskeyResult {
  credentialId: string;
  publicKeyBase64: string;
}

/** Creates a platform passkey and returns data for WPB device registration. */
export async function registerPasskey(holderId: string): Promise<RegisterPasskeyResult> {
  const options: PublicKeyCredentialCreationOptionsJSON = {
    rp: { id: walletRpId, name: walletRpName },
    user: {
      id: stringToBase64Url(holderId),
      name: holderId,
      displayName: holderId,
    },
    challenge: randomChallenge(),
    pubKeyCredParams: [{ alg: -7, type: "public-key" }],
    authenticatorSelection: {
      authenticatorAttachment: "platform",
      residentKey: "preferred",
      userVerification: "preferred",
    },
    timeout: 60_000,
    attestation: "none",
  };

  const registration = await startRegistration({ optionsJSON: options });
  const publicKeyBase64 = extractCosePublicKey(registration.response.attestationObject);

  return {
    credentialId: registration.id,
    publicKeyBase64,
  };
}

/** Signs a server-issued challenge with a known credential id. */
export async function assertPasskey(
  holderId: string,
  credentialId: string,
  challenge: string,
): Promise<Fido2AssertionPayload> {
  const options: PublicKeyCredentialRequestOptionsJSON = {
    challenge,
    rpId: walletRpId,
    allowCredentials: [{ id: credentialId, type: "public-key" }],
    userVerification: "preferred",
    timeout: 60_000,
  };

  const assertion = await startAuthentication({ optionsJSON: options });
  return toAssertionPayload(holderId, assertion);
}

/** Signs a challenge with a discoverable passkey when no credential id is stored locally. */
export async function assertPasskeyDiscoverable(
  holderId: string,
  challenge: string,
): Promise<Fido2AssertionPayload> {
  const options: PublicKeyCredentialRequestOptionsJSON = {
    challenge,
    rpId: walletRpId,
    userVerification: "preferred",
    timeout: 60_000,
  };

  const assertion = await startAuthentication({ optionsJSON: options });
  return toAssertionPayload(holderId, assertion);
}

function toAssertionPayload(
  holderId: string,
  assertion: AuthenticationResponseJSON,
): Fido2AssertionPayload {
  return {
    userId: holderId,
    id: assertion.id,
    clientDataJSON: assertion.response.clientDataJSON,
    authenticatorData: assertion.response.authenticatorData,
    signature: assertion.response.signature,
  };
}
