import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";

export function LoginPage() {
  const navigate = useNavigate();
  const { session, rememberedSession, busy, error, clearError, loginHolder } = useAuth();
  const [holderId, setHolderId] = useState(rememberedSession?.holderId ?? "");

  if (session) {
    return (
      <section className="page">
        <header className="page__header">
          <h1>Log in</h1>
          <p className="page__lead">
            You are already signed in as <code>{session.holderId}</code>.
          </p>
        </header>
        <div className="toolbar">
          <Link className="button" to="/settings">
            Open settings
          </Link>
        </div>
      </section>
    );
  }

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault();
    clearError();
    try {
      await loginHolder(holderId);
      navigate("/settings");
    } catch {
      /* surfaced via context */
    }
  }

  return (
    <section className="page">
      <header className="page__header">
        <h1>Log in</h1>
        <p className="page__lead">
          Sign in with your existing holder id and passkey. No new registration — only a WebAuthn
          unlock against WPB.
        </p>
      </header>

      {rememberedSession && (
        <div className="alert alert--info">
          <strong>Passkey on this browser</strong>
          <p>
            Last holder: <code>{rememberedSession.holderId}</code>. Use the same id below to pick
            the matching passkey.
          </p>
        </div>
      )}

      <form className="card form" onSubmit={(event) => void handleSubmit(event)}>
        <label className="form__field">
          <span className="form__label">Holder id</span>
          <input
            type="text"
            value={holderId}
            onChange={(event) => setHolderId(event.target.value)}
            placeholder="e.g. demo-holder-1"
            autoComplete="username"
            required
            disabled={busy}
          />
        </label>

        <p className="hint">
          First time here?{" "}
          <Link to="/onboarding">Create a new holder</Link> instead.
        </p>

        {error && (
          <div className="alert alert--error" role="alert">
            {error}
          </div>
        )}

        <div className="toolbar">
          <button type="submit" disabled={busy || holderId.trim().length === 0}>
            {busy ? "Signing in…" : "Log in with passkey"}
          </button>
        </div>
      </form>
    </section>
  );
}
