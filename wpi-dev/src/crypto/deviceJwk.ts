/** Generates a fresh P-256 EC public JWK for wallet unit bootstrap (DPoP device binding). */
export async function generateDevicePublicJwk(): Promise<string> {
  const keyPair = await crypto.subtle.generateKey(
    { name: "ECDSA", namedCurve: "P-256" },
    true,
    ["sign", "verify"],
  );
  const jwk = await crypto.subtle.exportKey("jwk", keyPair.publicKey);
  if (!jwk.x || !jwk.y) {
    throw new Error("Failed to export EC public JWK coordinates.");
  }
  return JSON.stringify({
    kty: "EC",
    crv: "P-256",
    x: jwk.x,
    y: jwk.y,
  });
}
