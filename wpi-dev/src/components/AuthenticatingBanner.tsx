import { useAuth } from "../auth/AuthContext";

export function AuthenticatingBanner() {
  const { busy } = useAuth();
  if (!busy) {
    return null;
  }
  return (
    <div className="auth-banner" role="status" aria-live="polite">
      Authenticating with passkey…
    </div>
  );
}
