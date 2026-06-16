import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";

export function OnboardingPage() {
  const navigate = useNavigate();
  const { session, busy, error, clearError, registerHolder } = useAuth();
  const [holderId, setHolderId] = useState("");

  if (session) {
    return (
      <section className="page">
        <header className="page__header">
          <h1>New holder</h1>
          <p className="page__lead">
            You are signed in as <code>{session.holderId}</code>. Sign out first if you want to
            register another holder.
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
      await registerHolder(holderId);
      navigate("/settings");
    } catch {
      /* surfaced via context */
    }
  }

  return (
    <section className="page">
      <header className="page__header">
        <h1>New holder</h1>
        <p className="page__lead">
          Choose a holder id and register a platform passkey for the first time on WPB.
        </p>
      </header>

      <form className="card form" onSubmit={(event) => void handleSubmit(event)}>
        <label className="form__field">
          <span className="form__label">Holder id</span>
          <input
            type="text"
            value={holderId}
            onChange={(event) => setHolderId(event.target.value)}
            placeholder="e.g. demo-holder-1"
            autoComplete="off"
            required
            disabled={busy}
          />
        </label>

        <p className="hint">
          Already registered? <Link to="/login">Log in with your passkey</Link>.
        </p>

        {error && (
          <div className="alert alert--error" role="alert">
            {error}
          </div>
        )}

        <div className="toolbar">
          <button type="submit" disabled={busy || holderId.trim().length === 0}>
            {busy ? "Registering passkey…" : "Register passkey"}
          </button>
        </div>
      </form>
    </section>
  );
}
