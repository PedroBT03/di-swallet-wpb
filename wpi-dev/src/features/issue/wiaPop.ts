import { loadDevicePrivateJwk, signWiaPopJwt } from "../../crypto/deviceJwk";
import type { IssuanceContext } from "../../types/openid4vci";

/** Signs a WIA PoP JWT for the current issuance session using the holder's device private key. */
export async function signIssuanceWiaPop(
  holderId: string,
  context: IssuanceContext,
): Promise<string> {
  const attestation = context.wia?.attestation;
  if (!attestation) {
    throw new Error("WIA is not attached to the issuance session yet.");
  }
  const privateJwkJson = loadDevicePrivateJwk(holderId);
  if (!privateJwkJson) {
    throw new Error(
      "Device private key is missing. Re-run wallet init on the Wallet page to generate a new device key pair.",
    );
  }
  const audience = context.credentialIssuerId ?? context.resolvedOffer?.credentialIssuerId ?? "issuer";
  return signWiaPopJwt({
    privateJwkJson,
    walletInstanceId: attestation.walletInstanceId,
    cnfJkt: attestation.cnfJkt,
    audience,
  });
}
