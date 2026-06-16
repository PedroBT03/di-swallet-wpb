import type { ReactNode } from "react";
import { Link } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";

export function AuthGate({ children }: { children: ReactNode }) {
  const { session } = useAuth();

  if (!session) {
    return (
      <section className="page">
        <header className="page__header">
          <h1>Sign in required</h1>
          <p className="page__lead">Log in with your holder passkey to use wallet features.</p>
        </header>
        <div className="toolbar">
          <Link className="button" to="/login">
            Log in
          </Link>
          <Link className="button button--secondary" to="/onboarding">
            New holder
          </Link>
        </div>
      </section>
    );
  }

  return <>{children}</>;
}
