import { ApiError } from "../api/client";

export function formatApiError(error: unknown): string {
  if (error instanceof ApiError) {
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

export function isIssuanceEligible(state: string | undefined): boolean {
  return state === "OPERATIONAL" || state === "VALID";
}
