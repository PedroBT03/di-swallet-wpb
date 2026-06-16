import { Link } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";

export function AuthStatus() {
  const { session, busy, signOut } = useAuth();

  if (!session) {
    return (
      <div className="auth-status auth-status--guest">
        <span className="auth-status__label">Signed out</span>
        <div className="auth-status__actions">
          <Link className="auth-status__action" to="/login">
            Log in
          </Link>
          <Link className="auth-status__action auth-status__action--muted" to="/onboarding">
            New holder
          </Link>
        </div>
      </div>
    );
  }

  return (
    <div className="auth-status auth-status--signed-in">
      <span className="auth-status__label">
        Holder <code>{session.holderId}</code>
      </span>
      {busy && <span className="auth-status__busy">Authenticating…</span>}
      <button
        type="button"
        className="auth-status__button"
        onClick={signOut}
        disabled={busy}
      >
        Sign out
      </button>
    </div>
  );
}
