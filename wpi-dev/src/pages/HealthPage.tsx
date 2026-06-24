import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { wpbExternalBase } from "../api/client";
import { fetchHealthSnapshot, type NamedHealthComponent } from "../api/health";
import {
  fetchWpbOperationalInfo,
  parseOpenId4VciDemoMode,
  parseOpenId4VpDemoMode,
  parseTransactionLogDekMode,
} from "../api/ops";
import { JsonPanel } from "../components/JsonPanel";
import type { HealthResponse } from "../types/health";
import type { WpbOperationalInfo } from "../api/ops";
import { formatApiError } from "../utils/apiError";

function statusBadgeClass(status: string | undefined): string {
  if (status === "UP") {
    return "status-badge status-badge--up";
  }
  if (status === "DOWN" || status === "OUT_OF_SERVICE") {
    return "status-badge status-badge--down";
  }
  if (status === "DEGRADED") {
    return "status-badge status-badge--unknown";
  }
  return "status-badge status-badge--unknown";
}

function statusLabel(status: string | undefined): string {
  return status && status.length > 0 ? status : "UNKNOWN";
}

function demoFlagBadge(enabled: boolean | null): { className: string; label: string } {
  if (enabled === true) {
    return { className: "status-badge status-badge--up", label: "ON" };
  }
  if (enabled === false) {
    return { className: "status-badge status-badge--down", label: "OFF" };
  }
  return { className: "status-badge status-badge--unknown", label: "UNKNOWN" };
}

function formatProfiles(profiles: string[] | undefined): string {
  if (!profiles || profiles.length === 0) {
    return "default";
  }
  return profiles.join(", ");
}

function componentDetailHint(component: NamedHealthComponent): string | null {
  const details = component.details;
  if (!details) {
    return null;
  }
  if (component.name === "hsm") {
    const keys = details.keyEntryCount;
    const token = details.tokenLabel;
    if (keys != null && token != null) {
      return `${keys} key(s) on ${String(token)}`;
    }
    if (typeof details.reason === "string") {
      return details.reason;
    }
  }
  if (component.name === "trustSnapshot" && typeof details.reason === "string") {
    return details.reason;
  }
  return null;
}

function displayComponentName(name: string): string {
  if (name === "hsm") {
    return "HSM (SoftHSM)";
  }
  if (name === "trustSnapshot") {
    return "Trust snapshot";
  }
  return name;
}

export function HealthPage() {
  const [health, setHealth] = useState<HealthResponse | null>(null);
  const [components, setComponents] = useState<NamedHealthComponent[]>([]);
  const [operational, setOperational] = useState<WpbOperationalInfo | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(true);

  const load = useCallback(async () => {
    setRefreshing(true);
    setError(null);
    try {
      const [healthSnapshot, infoData] = await Promise.all([
        fetchHealthSnapshot(),
        fetchWpbOperationalInfo().catch(() => null),
      ]);
      setHealth(healthSnapshot.aggregate);
      setComponents(healthSnapshot.components);
      setOperational(infoData);
    } catch (err) {
      setHealth(null);
      setComponents([]);
      setOperational(null);
      setError(
        formatApiError(err) || "Failed to reach WPB. Is `./gradlew :app:bootRun` running?",
      );
    } finally {
      setRefreshing(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const swaggerUrl = `${wpbExternalBase}/swagger-ui.html`;
  const vpDemo = parseOpenId4VpDemoMode(operational ?? {});
  const vciDemo = parseOpenId4VciDemoMode(operational ?? {});
  const logDekMode = parseTransactionLogDekMode(operational ?? {});
  const labConfig = operational?.operational;
  const vpDemoBadge = demoFlagBadge(vpDemo);
  const vciDemoBadge = demoFlagBadge(vciDemo);

  return (
    <section className="page page--health">
      <header className="page__header">
        <h1>Health</h1>
        <p className="page__lead">
          WPB reachability and lab configuration. No passkey required.
        </p>
      </header>

      {error ? (
        <div className="alert alert--error" role="alert">
          <strong>Connection failed</strong>
          <p>{error}</p>
          <p className="hint">
            Start PostgreSQL (<code>docker compose up -d</code>) and WPB from the repo root:{" "}
            <code>./gradlew :app:bootRun</code>
          </p>
        </div>
      ) : null}

      <div className="page-stack">
        <div className="card ops-scenario-card">
          <h2 className="card__title">Before you demo</h2>
          <ol className="ops-scenario-card__steps">
            <li>
              Confirm <strong>Overall</strong> is <strong>UP</strong> below, then continue to{" "}
              <Link to="/onboarding">Onboarding</Link> or <Link to="/login">Log in</Link>.
            </li>
            <li>
              Check <strong>HSM</strong> (SoftHSM reachable) and <strong>Trust snapshot</strong>{" "}
              (verifier trust material loaded).
            </li>
            <li>
              For local <Link to="/present">Present</Link> and <Link to="/issue">Issue</Link> flows,
              confirm OpenID4VP and OpenID4VCI <strong>demo mode</strong> in lab configuration.
            </li>
          </ol>
        </div>

        <div className="card">
          <h2 className="card__title">Backend status</h2>
          <p className="hint">
            From Spring Boot Actuator on <code>{wpbExternalBase}</code> (proxied through Vite).{" "}
            <strong>Overall</strong> is the aggregate WPB health. <strong>HSM</strong> and{" "}
            <strong>Trust snapshot</strong> are the two checks that matter most in this lab.
          </p>
          <div className="toolbar privacy-card__toolbar">
            <button
              type="button"
              className="button button--secondary"
              onClick={() => void load()}
              disabled={refreshing}
            >
              {refreshing ? (
                <>
                  <span className="btn-spinner" aria-hidden="true" />
                  Refreshing…
                </>
              ) : (
                "Refresh"
              )}
            </button>
            <a className="button button--secondary" href={swaggerUrl} target="_blank" rel="noreferrer">
              Open Swagger
            </a>
          </div>

          {refreshing && !health ? (
            <p className="hint">Loading health…</p>
          ) : health ? (
            <>
              <div className="wallet-stats ops-stats">
                <div className="wallet-stat">
                  <span className="wallet-stat__label">Overall</span>
                  <span className={statusBadgeClass(health.status)}>{statusLabel(health.status)}</span>
                </div>
                {components.map((component) => (
                  <div key={component.name} className="wallet-stat">
                    <span className="wallet-stat__label">{displayComponentName(component.name)}</span>
                    <span className={statusBadgeClass(component.status)}>
                      {statusLabel(component.status)}
                    </span>
                  </div>
                ))}
              </div>

              {components.length > 0 ? (
                <ul className="component-list">
                  {components.map((component) => {
                    const hint = componentDetailHint(component);
                    return (
                      <li key={component.name} className="component-list__item">
                        <div>
                          <span className="component-list__name">{displayComponentName(component.name)}</span>
                          {hint ? <div className="hint">{hint}</div> : null}
                        </div>
                        <span className={statusBadgeClass(component.status)}>
                          {statusLabel(component.status)}
                        </span>
                      </li>
                    );
                  })}
                </ul>
              ) : null}

              <JsonPanel title="Actuator health JSON" data={{ aggregate: health, components }} />
            </>
          ) : null}
        </div>

        <div className="card">
          <h2 className="card__title">Lab configuration</h2>
          <p className="hint">
            Non-secret flags from <code>GET /actuator/info</code>. Present and Issue pages read the
            same demo-mode values at runtime.
          </p>

          {refreshing && !operational ? (
            <p className="hint">Loading configuration…</p>
          ) : labConfig ? (
            <>
              <dl className="details-list ops-trust-details">
                <div className="details-list__row">
                  <dt>Spring profile</dt>
                  <dd>
                    <code>{formatProfiles(labConfig.profile)}</code>
                  </dd>
                </div>
                {labConfig.version ? (
                  <div className="details-list__row">
                    <dt>Build</dt>
                    <dd>
                      <code>
                        {labConfig.version}
                        {labConfig.gitCommit ? ` (${labConfig.gitCommit})` : ""}
                      </code>
                    </dd>
                  </div>
                ) : null}
                <div className="details-list__row">
                  <dt>OpenID4VP demo</dt>
                  <dd>
                    <span className={vpDemoBadge.className}>{vpDemoBadge.label}</span>
                  </dd>
                </div>
                <div className="details-list__row">
                  <dt>OpenID4VCI demo</dt>
                  <dd>
                    <span className={vciDemoBadge.className}>{vciDemoBadge.label}</span>
                  </dd>
                </div>
                <div className="details-list__row">
                  <dt>Transaction log DEK</dt>
                  <dd>
                    <code>{logDekMode ?? "unknown"}</code>
                  </dd>
                </div>
                {typeof labConfig.registryEnabled === "boolean" ? (
                  <div className="details-list__row">
                    <dt>RP registry</dt>
                    <dd>
                      <span
                        className={
                          labConfig.registryEnabled
                            ? "status-badge status-badge--up"
                            : "status-badge status-badge--unknown"
                        }
                      >
                        {labConfig.registryEnabled ? "ENABLED" : "OFF"}
                      </span>
                    </dd>
                  </div>
                ) : null}
                {labConfig.trustSourceMode ? (
                  <div className="details-list__row">
                    <dt>Trust source</dt>
                    <dd>
                      <code>{labConfig.trustSourceMode}</code>
                    </dd>
                  </div>
                ) : null}
              </dl>
              <JsonPanel title="Actuator info JSON" data={operational} />
            </>
          ) : (
            <p className="hint">Configuration details unavailable. Health may still be UP.</p>
          )}
        </div>
      </div>
    </section>
  );
}
