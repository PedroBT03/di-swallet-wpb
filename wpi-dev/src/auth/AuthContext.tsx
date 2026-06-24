import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from "react";
import { createHolderSession, fetchAuthChallenge, registerDevice } from "../api/auth";
import { ApiError } from "../api/client";
import { clearWalletState } from "../features/wallet/storage";
import {
  clearSession,
  forgetRememberedSession,
  loadRememberedSession,
  loadSession,
  saveHolderSessionToken,
  saveSession,
  withAuth,
  type HolderSession,
} from "../auth/session";
import { assertWithServerChallenge, formatWebAuthnError, registerPasskey } from "../auth/webauthn";
import type { Fido2AssertionPayload } from "../types/fido2";
import { formatApiError } from "../utils/apiError";

interface AuthContextValue {
  session: HolderSession | null;
  rememberedSession: HolderSession | null;
  busy: boolean;
  error: string | null;
  clearError: () => void;
  registerHolder: (holderId: string) => Promise<void>;
  loginHolder: (holderId: string) => Promise<void>;
  reregisterPasskey: () => Promise<void>;
  /** Fresh WebAuthn assertion for sole-control operations (consent, HSM). */
  unlock: () => Promise<Headers>;
  /** Re-opens a holder session token (dashboard APIs) after login or 401. */
  refreshHolderSession: () => Promise<void>;
  signOut: () => void;
  forgetDevice: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

function normalizeApiError(error: unknown): string {
  if (error instanceof ApiError) {
    return formatApiError(error);
  }
  return formatWebAuthnError(error);
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<HolderSession | null>(() => loadSession());
  const [rememberedSession, setRememberedSession] = useState<HolderSession | null>(() =>
    loadRememberedSession(),
  );
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const clearError = useCallback(() => setError(null), []);

  const persistHolderSession = useCallback(async (assertion: Fido2AssertionPayload) => {
    const holderSession = await createHolderSession(assertion);
    saveHolderSessionToken(holderSession.sessionToken, holderSession.expiresAt);
  }, []);

  const authenticateWithPasskey = useCallback(
    async (holderId: string, credentialId?: string): Promise<Fido2AssertionPayload> => {
      const challengeResponse = await fetchAuthChallenge(holderId, credentialId);
      return assertWithServerChallenge(holderId, challengeResponse);
    },
    [],
  );

  const refreshHolderSession = useCallback(async () => {
    if (!session) {
      throw new Error("No holder session. Log in first.");
    }
    setBusy(true);
    setError(null);
    try {
      const assertion = await authenticateWithPasskey(session.holderId, session.credentialId);
      await persistHolderSession(assertion);
    } catch (err) {
      const message = normalizeApiError(err);
      setError(message);
      throw err;
    } finally {
      setBusy(false);
    }
  }, [authenticateWithPasskey, persistHolderSession, session]);

  const completeRegistration = useCallback(
    async (holderId: string) => {
      const trimmed = holderId.trim();
      if (!trimmed) {
        throw new Error("Holder id is required.");
      }

      const passkey = await registerPasskey(trimmed);
      const device = await registerDevice(
        trimmed,
        passkey.credentialId,
        passkey.publicKeyBase64,
        true,
      );

      const nextSession: HolderSession = {
        holderId: trimmed,
        credentialId: passkey.credentialId,
        publicKeyBase64: passkey.publicKeyBase64,
        userDeviceId: device.id,
      };
      saveSession(nextSession);
      setSession(nextSession);
      setRememberedSession(nextSession);

      const assertion = await authenticateWithPasskey(trimmed, passkey.credentialId);
      await persistHolderSession(assertion);
    },
    [authenticateWithPasskey, persistHolderSession],
  );

  const loginHolder = useCallback(
    async (holderId: string) => {
      const trimmed = holderId.trim();
      if (!trimmed) {
        throw new Error("Holder id is required.");
      }

      setBusy(true);
      setError(null);
      try {
        const remembered = loadRememberedSession();

        const assertion = await authenticateWithPasskey(
          trimmed,
          remembered?.holderId === trimmed ? remembered.credentialId : undefined,
        );
        await persistHolderSession(assertion);

        const publicKeyBase64 =
          remembered?.holderId === trimmed ? remembered.publicKeyBase64 : undefined;
        if (publicKeyBase64) {
          await registerDevice(trimmed, assertion.id, publicKeyBase64, true);
        }

        const nextSession: HolderSession = {
          holderId: trimmed,
          credentialId: assertion.id,
          publicKeyBase64,
          userDeviceId:
            remembered?.holderId === trimmed ? remembered.userDeviceId : undefined,
        };
        saveSession(nextSession);
        setSession(nextSession);
        setRememberedSession(nextSession);
      } catch (err) {
        const message = normalizeApiError(err);
        setError(message);
        throw err;
      } finally {
        setBusy(false);
      }
    },
    [authenticateWithPasskey, persistHolderSession],
  );

  const registerHolder = useCallback(
    async (holderId: string) => {
      setBusy(true);
      setError(null);
      try {
        await completeRegistration(holderId);
      } catch (err) {
        const message = normalizeApiError(err);
        setError(message);
        throw err;
      } finally {
        setBusy(false);
      }
    },
    [completeRegistration],
  );

  const reregisterPasskey = useCallback(async () => {
    if (!session) {
      throw new Error("No holder session to re-register.");
    }
    setBusy(true);
    setError(null);
    try {
      await completeRegistration(session.holderId);
    } catch (err) {
      const message = normalizeApiError(err);
      setError(message);
      throw err;
    } finally {
      setBusy(false);
    }
  }, [completeRegistration, session]);

  const unlock = useCallback(async (): Promise<Headers> => {
    if (!session) {
      throw new Error("Log in before performing this action.");
    }
    setBusy(true);
    setError(null);
    try {
      const assertion = await authenticateWithPasskey(session.holderId, session.credentialId);
      return withAuth(assertion);
    } catch (err) {
      const message = normalizeApiError(err);
      setError(message);
      throw err;
    } finally {
      setBusy(false);
    }
  }, [authenticateWithPasskey, session]);

  const signOut = useCallback(() => {
    clearSession();
    setSession(null);
    setError(null);
  }, []);

  const forgetDevice = useCallback(() => {
    const holderId = session?.holderId ?? rememberedSession?.holderId;
    forgetRememberedSession();
    if (holderId) {
      clearWalletState(holderId);
    }
    setSession(null);
    setRememberedSession(null);
    setError(null);
  }, [session?.holderId, rememberedSession?.holderId]);

  const value = useMemo(
    () => ({
      session,
      rememberedSession,
      busy,
      error,
      clearError,
      registerHolder,
      loginHolder,
      reregisterPasskey,
      unlock,
      refreshHolderSession,
      signOut,
      forgetDevice,
    }),
    [
      session,
      rememberedSession,
      busy,
      error,
      clearError,
      registerHolder,
      loginHolder,
      reregisterPasskey,
      unlock,
      refreshHolderSession,
      signOut,
      forgetDevice,
    ],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error("useAuth must be used within AuthProvider");
  }
  return context;
}
