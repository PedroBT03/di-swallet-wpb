/** PBKDF2-SHA256 derivation aligned with WPB HolderLogKeyDerivation (120k iterations). */

const ITERATIONS = 120_000;
const KEY_BITS = 256;

export async function deriveHolderLogKey(holderId: string, password: string): Promise<Uint8Array> {
  const salt = new Uint8Array(
    await crypto.subtle.digest("SHA-256", new TextEncoder().encode(holderId)),
  );
  const keyMaterial = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(password),
    "PBKDF2",
    false,
    ["deriveBits"],
  );
  const bits = await crypto.subtle.deriveBits(
    { name: "PBKDF2", salt, iterations: ITERATIONS, hash: "SHA-256" },
    keyMaterial,
    KEY_BITS,
  );
  return new Uint8Array(bits);
}

export function bytesToBase64(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) {
    binary += String.fromCharCode(byte);
  }
  return btoa(binary);
}
