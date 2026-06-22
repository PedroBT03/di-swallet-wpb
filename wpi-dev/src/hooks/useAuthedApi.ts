import { useCallback } from "react";
import { useAuth } from "../auth/AuthContext";
import { ApiError } from "../api/client";
import { withSessionAuth } from "../auth/session";

/**
 * Session auth (WIAM_15): holder session token from login, no extra passkey prompt.
 * Sole control (WIAM_14): fresh WebAuthn assertion for consent and HSM operations.
 */
export function useAuthedApi() {
  const { session, unlock, refreshHolderSession, busy, clearError } = useAuth();

  const withApiAuth = useCallback(
    async <T>(action: (headers: Headers) => Promise<T>): Promise<T> => {
      const headers = withSessionAuth();
      const hadSessionToken = headers.has("X-Wallet-Session");
      try {
        return await action(headers);
      } catch (err) {
        if (err instanceof ApiError && err.status === 401 && session && hadSessionToken) {
          await refreshHolderSession();
          return action(withSessionAuth());
        }
        throw err;
      }
    },
    [refreshHolderSession, session],
  );

  const withSoleControl = useCallback(
    async <T>(action: (headers: Headers) => Promise<T>): Promise<T> => {
      const headers = await unlock();
      return action(headers);
    },
    [unlock],
  );

  return { session, withApiAuth, withSoleControl, busy, clearError };
}
