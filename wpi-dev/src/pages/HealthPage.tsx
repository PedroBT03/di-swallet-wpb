import { useCallback, useEffect, useState } from "react";
import { fetchHealth } from "../api/health";
import { wpbExternalBase } from "../api/client";
import type { HealthResponse } from "../types/health";
import { formatApiError } from "../utils/apiError";

function statusClass(status: string | undefined): string {
  if (status === "UP") return "status-badge status-badge--up";
  if (status === "DOWN") return "status-badge status-badge--down";
  return "status-badge status-badge--unknown";
}

export function HealthPage() {
  const [health, setHealth] = useState<HealthResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await fetchHealth();
      setHealth(data);
    } catch (err) {
      setHealth(null);
      setError(formatApiError(err) || "Failed to reach WPB. Is `./gradlew :app:bootRun` running?");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const swaggerUrl = `${wpbExternalBase}/swagger-ui.html`;

  return (
    <section className="page">
      <header className="page__header">
        <h1>Backend health</h1>
        <p className="page__lead">
          Proxied via Vite to <code>{wpbExternalBase}</code>. API calls from this UI use the dev
          proxy so WPB does not need CORS.
        </p>
      </header>

      <div className="toolbar">
        <button type="button" onClick={() => void load()} disabled={loading}>
          {loading ? "Refreshing…" : "Refresh"}
        </button>
        <a className="button button--secondary" href={swaggerUrl} target="_blank" rel="noreferrer">
          Open WPB Swagger
        </a>
      </div>

      {error && (
        <div className="alert alert--error" role="alert">
          <strong>Connection failed</strong>
          <p>{error}</p>
          <p className="hint">
            Start the wallet backend from the repo root:{" "}
            <code>./gradlew :app:bootRun</code>
          </p>
        </div>
      )}

      {health && (
        <div className="card">
          <div className="card__row">
            <span>Overall</span>
            <span className={statusClass(health.status)}>{health.status}</span>
          </div>

          {health.components && Object.keys(health.components).length > 0 && (
            <ul className="component-list">
              {Object.entries(health.components).map(([name, component]) => (
                <li key={name} className="component-list__item">
                  <span className="component-list__name">{name}</span>
                  <span className={statusClass(component.status)}>{component.status}</span>
                </li>
              ))}
            </ul>
          )}

          <details className="raw-json">
            <summary>Raw JSON</summary>
            <pre>{JSON.stringify(health, null, 2)}</pre>
          </details>
        </div>
      )}
    </section>
  );
}
