import { ApiError } from "../api/client";

export function formatApiError(error: unknown): string {
  if (error instanceof ApiError) {
    const detail = sanitizeMessage(extractApiErrorDetail(error) || error.message);
    switch (error.status) {
      case 415:
        return detail.length > 0
          ? detail
          : "Unsupported request format. Try signing out and logging in again.";
      case 401:
        return formatUnauthorizedMessage(detail);
      case 403:
        return formatForbiddenMessage(detail);
      case 404:
        return detail.length > 0
          ? `Not found: ${detail}`
          : "The requested resource was not found.";
      case 400:
        return detail.length > 0 ? detail : "The request was invalid. Check your input and try again.";
      case 409:
        return formatConflictMessage(detail);
      case 422:
        return detail.length > 0 ? detail : "The server could not process this request.";
      case 500:
      case 502:
      case 503:
        return detail.length > 0
          ? `Server error: ${detail}`
          : "The server encountered an error. Try again or check that WPB is running.";
      default:
        return detail.length > 0 ? detail : "Something went wrong. Please try again.";
    }
  }
  if (error instanceof Error) {
    return sanitizeMessage(error.message);
  }
  return "Request failed.";
}

function extractApiErrorDetail(error: ApiError): string {
  if (typeof error.body === "string" && error.body.length > 0) {
    return error.body;
  }
  if (typeof error.body === "object" && error.body !== null) {
    const record = error.body as Record<string, unknown>;
    for (const field of ["message", "detail", "title", "reason"] as const) {
      const value = record[field];
      if (typeof value === "string" && value.length > 0 && !isGenericHttpReasonPhrase(value)) {
        return value;
      }
    }
    const errorField = record.error;
    if (typeof errorField === "string" && errorField.length > 0 && !isGenericHttpReasonPhrase(errorField)) {
      return errorField;
    }
  }
  return "";
}

function isGenericHttpReasonPhrase(text: string): boolean {
  const lower = text.trim().toLowerCase();
  return [
    "bad request",
    "conflict",
    "forbidden",
    "internal server error",
    "not found",
    "service unavailable",
    "unauthorized",
    "unprocessable entity",
  ].includes(lower);
}

function formatConflictMessage(detail: string): string {
  const lower = detail.toLowerCase();
  if (lower.includes("issuer-managed")) {
    return (
      "This credential was issued via OID4VCI and is managed by the issuer status list. " +
      "The wallet cannot update that list from here. Use Delete to remove it from the wallet, " +
      "or contact the issuer for formal revocation."
    );
  }
  if (lower.includes("status list index")) {
    return "This credential cannot be revoked because it has no wallet status list index.";
  }
  if (detail.length > 0) {
    return detail;
  }
  return "This action conflicts with the current wallet state.";
}

function sanitizeMessage(message: string): string {
  const trimmed = message.trim();
  if (trimmed.length === 0) {
    return "";
  }
  const withoutLeadingStatus = trimmed.replace(/^\d{3}\s+[A-Za-z][A-Za-z\s]*(?:\.\s*)?/, "");
  return withoutLeadingStatus
    .replace(/\(\d{3}\)/g, "")
    .replace(/\b\d{3}\s+Forbidden\b/gi, "")
    .replace(/\b\d{3}\s+Unauthorized\b/gi, "")
    .replace(/\s+/g, " ")
    .trim();
}

function formatUnauthorizedMessage(detail: string): string {
  const lower = detail.toLowerCase();
  if (lower.includes("authorization context") || lower.includes("holder session")) {
    return (
      "Holder session missing or expired. Sign out and log in again with your passkey once; " +
      "after that, wallet sync and other dashboard views will not ask again until the session expires."
    );
  }
  const explanation =
    detail.length > 0
      ? detail
      : "Your passkey unlock was not accepted for this holder.";
  return (
    `${explanation} ` +
    "Try Wallet → Sync from server with the same holder. If that fails, sign out and use Settings → " +
    "Re-register passkey (needed after a WPB database reset or if this browser never stored the device key)."
  );
}

function formatForbiddenMessage(detail: string): string {
  const lower = detail.toLowerCase();
  if (lower.includes("revoked") || lower.includes("status list")) {
    return (
      "This HSM key has been revoked and can no longer sign data or issue credentials. " +
      "Use Ensure HSM key to issue a new active key, or Sync from server to refresh the status shown in the wallet."
    );
  }
  if (detail.length > 0) {
    return `You are not allowed to perform this action. ${detail}`;
  }
  return "You are not allowed to perform this action with the current holder session.";
}

export function isIssuanceEligible(state: string | undefined): boolean {
  return state === "OPERATIONAL" || state === "VALID";
}
