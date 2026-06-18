import { ApiError } from "../api/client";

export function formatApiError(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 401) {
      return formatFido2UnauthorizedMessage(error);
    }
    if (typeof error.body === "string" && error.body.length > 0) {
      return error.body;
    }
    if (typeof error.body === "object" && error.body !== null && "message" in error.body) {
      const message = (error.body as { message?: unknown }).message;
      if (typeof message === "string" && message.length > 0) {
        return message;
      }
    }
    return error.message;
  }
  if (error instanceof Error) {
    return error.message;
  }
  return "Request failed.";
}

function formatFido2UnauthorizedMessage(error: ApiError): string {
  const detail =
    typeof error.body === "object" && error.body !== null && "message" in error.body
      ? String((error.body as { message?: unknown }).message ?? "")
      : typeof error.body === "string"
        ? error.body
        : error.message;
  return (
    `Passkey unlock was rejected (401). ${detail} ` +
    "Try Wallet → Unlock & sync with the same holder. If that fails, sign out and use Settings → " +
    "Re-register passkey (needed after a WPB database reset or if this browser never stored the device key)."
  );
}

export function isIssuanceEligible(state: string | undefined): boolean {
  return state === "OPERATIONAL" || state === "VALID";
}
