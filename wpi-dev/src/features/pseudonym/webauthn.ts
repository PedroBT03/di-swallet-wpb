import { startRegistration } from "@simplewebauthn/browser";
import type { RegistrationOptionsResponse } from "../../types/pseudonym";

/** Runs a WebAuthn create ceremony for a pending pseudonym slot. */
export async function runPseudonymRegistration(
  options: RegistrationOptionsResponse,
  displayName: string,
): Promise<string> {
  const credential = await startRegistration({
    optionsJSON: {
      challenge: options.challenge,
      rp: { id: options.rpId, name: options.rpName },
      user: {
        id: options.userHandle,
        name: displayName,
        displayName,
      },
      pubKeyCredParams: options.pubKeyCredParams.map((entry) => ({
        alg: Number(entry.alg),
        type: "public-key" as const,
      })),
      timeout: options.timeout,
      authenticatorSelection: {
        authenticatorAttachment: "platform",
        residentKey: "preferred",
        userVerification: "preferred",
      },
    },
  });
  return credential.response.clientDataJSON;
}
