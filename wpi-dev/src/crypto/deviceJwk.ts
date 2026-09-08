import { bytesToBase64Url, stringToBase64Url } from "../auth/base64";

export interface DeviceKeyPair {
  publicJwkJson: string;
  privateJwkJson: string;
}

const devicePrivateJwkKey = (holderId: string) => `wpi-dev.devicePrivateJwk.${holderId}`;

/** Generates a fresh P-256 EC key pair for wallet unit bootstrap (DPoP device binding). */
export async function generateDeviceKeyPair(): Promise<DeviceKeyPair> {
  const keyPair = await crypto.subtle.generateKey(
    { name: "ECDSA", namedCurve: "P-256" },
    true,
    ["sign", "verify"],
  );
  const publicJwk = await crypto.subtle.exportKey("jwk", keyPair.publicKey);
  const privateJwk = await crypto.subtle.exportKey("jwk", keyPair.privateKey);
  if (!publicJwk.x || !publicJwk.y || !privateJwk.d) {
    throw new Error("Failed to export EC JWK coordinates.");
  }
  return {
    publicJwkJson: JSON.stringify({
      kty: "EC",
      crv: "P-256",
      x: publicJwk.x,
      y: publicJwk.y,
    }),
    privateJwkJson: JSON.stringify({
      kty: "EC",
      crv: "P-256",
      x: publicJwk.x,
      y: publicJwk.y,
      d: privateJwk.d,
    }),
  };
}

/** @deprecated Use generateDeviceKeyPair and persist the private JWK for PoP signing. */
export async function generateDevicePublicJwk(): Promise<string> {
  const pair = await generateDeviceKeyPair();
  return pair.publicJwkJson;
}

export function saveDevicePrivateJwk(holderId: string, privateJwkJson: string): void {
  localStorage.setItem(devicePrivateJwkKey(holderId), privateJwkJson);
}

export function loadDevicePrivateJwk(holderId: string): string | null {
  return localStorage.getItem(devicePrivateJwkKey(holderId));
}

export function clearDevicePrivateJwk(holderId: string): void {
  localStorage.removeItem(devicePrivateJwkKey(holderId));
}

export interface WiaPopSignParams {
  privateJwkJson: string;
  walletInstanceId: string;
  cnfJkt: string;
  audience: string;
  ttlSeconds?: number;
}

/** Signs a Wallet Instance Attestation PoP JWT with the device (WIA cnf) private key. */
export async function signWiaPopJwt(params: WiaPopSignParams): Promise<string> {
  const privateJwk = JSON.parse(params.privateJwkJson) as JsonWebKey;
  if (!privateJwk.d) {
    throw new Error("Device private JWK is missing the d coordinate.");
  }
  const privateKey = await crypto.subtle.importKey(
    "jwk",
    privateJwk,
    { name: "ECDSA", namedCurve: "P-256" },
    false,
    ["sign"],
  );
  const now = Math.floor(Date.now() / 1000);
  const exp = now + (params.ttlSeconds ?? 300);
  const header = stringToBase64Url(
    JSON.stringify({
      alg: "ES256",
      typ: "oauth-client-attestation-pop+jwt",
    }),
  );
  const payload = stringToBase64Url(
    JSON.stringify({
      iss: params.walletInstanceId,
      iat: now,
      exp,
      aud: params.audience,
      cnf: { jkt: params.cnfJkt },
    }),
  );
  const signingInput = `${header}.${payload}`;
  const signature = await crypto.subtle.sign(
    { name: "ECDSA", hash: "SHA-256" },
    privateKey,
    new TextEncoder().encode(signingInput),
  );
  const rawSignature = ecdsaSignatureToJwsRaw(new Uint8Array(signature));
  return `${signingInput}.${bytesToBase64Url(rawSignature)}`;
}

/**
 * Web Crypto returns IEEE P1363 raw R||S (64 bytes for P-256) in Chromium/Node;
 * some stacks still emit ASN.1 DER - accept both for JWS ES256.
 */
function ecdsaSignatureToJwsRaw(signature: Uint8Array): Uint8Array {
  if (signature.length === 64) {
    return signature;
  }
  if (signature[0] === 0x30) {
    return derEcdsaSignatureToRaw(signature);
  }
  throw new Error("Invalid ECDSA signature format.");
}

/** Converts an ASN.1 DER ECDSA signature to the raw R||S form used in JWS ES256. */
function derEcdsaSignatureToRaw(der: Uint8Array): Uint8Array {
  let offset = 0;
  if (der[offset++] !== 0x30) {
    throw new Error("Invalid ECDSA DER signature.");
  }
  const sequenceLength = readDerLength(der, offset);
  offset += sequenceLength.bytesRead;
  if (der[offset++] !== 0x02) {
    throw new Error("Invalid ECDSA DER signature: missing R integer.");
  }
  const rLength = readDerLength(der, offset);
  offset += rLength.bytesRead;
  const r = der.slice(offset, offset + rLength.length);
  offset += rLength.length;
  if (der[offset++] !== 0x02) {
    throw new Error("Invalid ECDSA DER signature: missing S integer.");
  }
  const sLength = readDerLength(der, offset);
  offset += sLength.bytesRead;
  const s = der.slice(offset, offset + sLength.length);
  const raw = new Uint8Array(64);
  raw.set(fixedCoordinate32(trimLeadingZeros(r)), 0);
  raw.set(fixedCoordinate32(trimLeadingZeros(s)), 32);
  return raw;
}

function fixedCoordinate32(bytes: Uint8Array): Uint8Array {
  if (bytes.length > 32) {
    throw new Error("ECDSA coordinate exceeds 32 bytes.");
  }
  const out = new Uint8Array(32);
  out.set(bytes, 32 - bytes.length);
  return out;
}

function readDerLength(bytes: Uint8Array, offset: number): { length: number; bytesRead: number } {
  const first = bytes[offset];
  if ((first & 0x80) === 0) {
    return { length: first, bytesRead: 1 };
  }
  const numBytes = first & 0x7f;
  let length = 0;
  for (let index = 0; index < numBytes; index += 1) {
    length = (length << 8) | bytes[offset + 1 + index];
  }
  return { length, bytesRead: 1 + numBytes };
}

function trimLeadingZeros(bytes: Uint8Array): Uint8Array {
  let start = 0;
  while (start < bytes.length - 1 && bytes[start] === 0) {
    start += 1;
  }
  return bytes.slice(start);
}
