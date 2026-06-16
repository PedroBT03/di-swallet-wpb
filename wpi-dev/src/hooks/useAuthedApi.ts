import { useCallback } from "react";
import { useAuth } from "../auth/AuthContext";

/** Runs a mutating or server-sync WPB call after FIDO2 unlock. */
export function useAuthedApi() {
  const { session, unlock, busy, clearError } = useAuth();

  const withProtectedAction = useCallback(
    async <T>(action: (headers: Headers) => Promise<T>): Promise<T> => {
      const headers = await unlock();
      return action(headers);
    },
    [unlock],
  );

  return { session, withProtectedAction, busy, clearError };
}
