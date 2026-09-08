import { useCallback, useEffect, useMemo, useState } from "react";
import { wpbExternalBase } from "../api/client";
import { fetchHealthSnapshot, type NamedHealthComponent } from "../api/health";
import {
  fetchWpbOperationalInfo,
  parseOpenId4VciDemoMode,
  parseOpenId4VpDemoMode,
} from "../api/ops";
import { JsonPanel } from "../components/JsonPanel";
import type { HealthResponse } from "../types/health";
import type { WpbOperationalInfo } from "../api/ops";
import { formatApiError } from "../utils/apiError";

type ReadinessLevel = "ready" | "blocked" | "warning" | "unknown";

interface ReadinessRow {
  id: string;
  label: string;
  level: ReadinessLevel;
  detail: string;
}

const LAB_COMPONENT_ORDER = ["db", "hsm", "trustSnapshot"] as const;

function healthLevel(status: string | undefined): ReadinessLevel {
  if (status === "UP") {
    return "ready";
  }
  if (status === "DEGRADED") {
    return "warning";
  }
  if (status === "DOWN" || status === "OUT_OF_SERVICE") {
    return "blocked";
  }
  return "unknown";
}

function levelBadgeClass(level: ReadinessLevel): string {
  switch (level) {
    case "ready":
      return "status-badge status-badge--up";
    case "blocked":
      return "status-badge status-badge--down";
    case "warning":
      return "status-badge status-badge--unknown";
    default:
      return "status-badge status-badge--unknown";
  }
}

function levelLabel(level: ReadinessLevel): string {
  switch (level) {
    case "ready":
      return "OK";
    case "blocked":
      return "BLOCKED";
    case "warning":
      return "DEGRADED";
    default:
      return "UNKNOWN";
  }
}

function demoLevel(enabled: boolean | null): ReadinessLevel {
  if (enabled === true) {
    return "ready";
  }
  if (enabled === false) {
    return "blocked";
  }
  return "unknown";
}

function displayComponentName(name: string): string {
  switch (name) {
    case "db":
      return "PostgreSQL";
    case "hsm":
      return "HSM (SoftHSM)";
    case "trustSnapshot":
      return "Trust snapshot";
    default:
      return name;
  }
}

function componentDetail(component: NamedHealthComponent): string {
  const details = component.details;
  if (component.name === "hsm" && details) {
    const keys = details.keyEntryCount;
    const token = details.tokenLabel;
    if (keys != null && token != null) {
      return `${keys} key(s) on token ${String(token)}`;
    }
    if (typeof details.reason === "string") {
      return details.reason;
    }
  }
  if (component.name === "trustSnapshot" && details) {
    const parts: string[] = [];
    if (typeof details.ageSeconds === "number") {
      parts.push(`age ${details.ageSeconds}s`);
    }
    if (typeof details.reason === "string") {
      parts.push(details.reason);
    }
    if (parts.length > 0) {
      return parts.join(" · ");
    }
  }
  if (component.name === "db" && details && typeof details.database === "string") {
    return details.database;
  }
  if (component.status === "UP") {
    return "Reachable";
  }
  return component.status ?? "No details from actuator";
}

function buildReadinessRows(
  health: HealthResponse | null,
  components: NamedHealthComponent[],
  operational: WpbOperationalInfo | null,
): ReadinessRow[] {
  const rows: ReadinessRow[] = [];

  rows.push({
    id: "wpb",
    label: "WPB API",
    level: health ? healthLevel(health.status) : "unknown",
    detail: health?.status === "UP" ? `Listening on ${wpbExternalBase}` : "Backend not reachable",
  });

  const byName = new Map(components.map((component) => [component.name, component]));
  for (const name of LAB_COMPONENT_ORDER) {
    const component = byName.get(name);
    rows.push({
      id: name,
      label: displayComponentName(name),
      level: component ? healthLevel(component.status) : "unknown",
      detail: component
        ? componentDetail(component)
        : "Component not reported - restart WPB after pulling latest dev profile settings",
    });
  }

  const vpDemo = parseOpenId4VpDemoMode(operational ?? {});
  rows.push({
    id: "demo-vp",
    label: "Present demo mode",
    level: demoLevel(vpDemo),
    detail:
      vpDemo === true
        ? "Simulated verifier flows enabled"
        : vpDemo === false
          ? "Start WPB with --wpb.openid4vp.demo-mode=true for local Present"
          : "Not reported by /actuator/info",
  });

  const vciDemo = parseOpenId4VciDemoMode(operational ?? {});
  rows.push({
    id: "demo-vci",
    label: "Issue demo mode",
    level: demoLevel(vciDemo),
    detail:
      vciDemo === true
        ? "Simulated issuer flows enabled"
        : vciDemo === false
          ? "Start WPB with --wpb.openid4vci.demo-mode=true for local Issue"
          : "Not reported by /actuator/info",
  });

  return rows;
}

function formatProfiles(profiles: string[] | undefined): string {
  if (!profiles || profiles.length === 0) {
    return "dev (default)";
  }
  return profiles.join(", ");
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

  const rows = useMemo(
    () => buildReadinessRows(health, components, operational),
    [health, components, operational],
  );
  const blockedCount = rows.filter((row) => row.level === "blocked").length;
  const labReady = rows.every((row) => row.level === "ready" || row.level === "warning");
  const labConfig = operational?.operational;
  const swaggerUrl = `${wpbExternalBase}/swagger-ui.html`;

  return (
    <section className="page page--health">
      <header className="page__header">
        <h1>Lab status</h1>
        <p className="page__lead">
          Quick check that WPB, PostgreSQL, SoftHSM, and demo flags are ready before onboarding,
          Present, or Issue.
        </p>
      </header>

      {error ? (
        <div className="alert alert--error" role="alert">
          <strong>Cannot reach WPB</strong>
          <p>{error}</p>
          <p className="hint">
            Start PostgreSQL with <code>docker compose up -d</code>, then WPB:{" "}
            <code>./gradlew :app:bootRun</code>
          </p>
        </div>
      ) : null}

      <div className="page-stack">
        <div className="card">
          <div className="wallet-panel__header">
            <h2 className="card__title">Readiness</h2>
            <div className="wallet-panel__actions">
              <button
                type="button"
                className="button button--secondary"
                onClick={() => void load()}
                disabled={refreshing}
              >
                {refreshing ? "Refreshing…" : "Refresh"}
              </button>
              <a className="button button--secondary" href={swaggerUrl} target="_blank" rel="noreferrer">
                Swagger
              </a>
            </div>
          </div>

          {!error && !refreshing ? (
            <p className="hint" role="status">
              {labReady
                ? "All checks passed."
                : blockedCount > 0
                  ? `${blockedCount} check(s) need attention before demo flows will work.`
                  : "Some checks could not be resolved. See details below."}
            </p>
          ) : null}

          <ul className="component-list">
            {rows.map((row) => (
              <li key={row.id} className="component-list__item">
                <div>
                  <span className="component-list__name">{row.label}</span>
                  <div className="hint">{row.detail}</div>
                </div>
                <span className={levelBadgeClass(row.level)}>{levelLabel(row.level)}</span>
              </li>
            ))}
          </ul>
        </div>

        {labConfig ? (
          <details className="present-dev-details">
            <summary>Build &amp; profile</summary>
            <div className="present-dev-details__body">
              <dl className="details-list">
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
                {labConfig.trustSourceMode ? (
                  <div className="details-list__row">
                    <dt>Trust source</dt>
                    <dd>
                      <code>{labConfig.trustSourceMode}</code>
                    </dd>
                  </div>
                ) : null}
              </dl>
            </div>
          </details>
        ) : null}

        {health || operational ? (
          <details className="present-dev-details">
            <summary>Actuator raw JSON</summary>
            <div className="present-dev-details__body">
              {health ? <JsonPanel title="GET /actuator/health" data={health} /> : null}
              {operational ? <JsonPanel title="GET /actuator/info" data={operational} /> : null}
            </div>
          </details>
        ) : null}
      </div>
    </section>
  );
}
