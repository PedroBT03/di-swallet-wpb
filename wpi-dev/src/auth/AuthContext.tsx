import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from "react";
import { fetchAuthChallenge, registerDevice } from "../api/auth";
import { ApiError } from "../api/client";
import { clearSession, forgetRememberedSession, loadRememberedSession, loadSession, saveSession, withAuth, type HolderSession } from "../auth/session";
import { assertWithServerChallenge, formatWebAuthnError, registerPasskey } from "../auth/webauthn";
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
  unlock: () => Promise<Headers>;
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

  const completeRegistration = useCallback(async (holderId: string) => {
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
  }, []);

  const loginHolder = useCallback(async (holderId: string) => {
    const trimmed = holderId.trim();
    if (!trimmed) {
      throw new Error("Holder id is required.");
    }

    setBusy(true);
    setError(null);
    try {
      const remembered = loadRememberedSession();

      const challengeResponse = await fetchAuthChallenge(
        trimmed,
        remembered?.holderId === trimmed ? remembered.credentialId : undefined,
      );
      const assertion = await assertWithServerChallenge(trimmed, challengeResponse);

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
  }, []);

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
      throw new Error("Register a passkey before unlocking.");
    }
    setBusy(true);
    setError(null);
    try {
      const challengeResponse = await fetchAuthChallenge(
        session.holderId,
        session.credentialId,
      );
      const assertion = await assertWithServerChallenge(session.holderId, challengeResponse);
      return withAuth(assertion);
    } catch (err) {
      const message = normalizeApiError(err);
      setError(message);
      throw err;
    } finally {
      setBusy(false);
    }
  }, [session]);

  const signOut = useCallback(() => {
    clearSession();
    setSession(null);
    setError(null);
  }, []);

  const forgetDevice = useCallback(() => {
    forgetRememberedSession();
    setSession(null);
    setRememberedSession(null);
    setError(null);
  }, []);

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
