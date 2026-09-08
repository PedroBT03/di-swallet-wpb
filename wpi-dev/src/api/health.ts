import type { HealthComponent, HealthResponse } from "../types/health";
import { apiFetch } from "./client";

export function fetchHealth(): Promise<HealthResponse> {
  return apiFetch<HealthResponse>("/actuator/health", { raw: true });
}

export interface NamedHealthComponent extends HealthComponent {
  name: string;
}

/** Fetches a single actuator health contributor (public in WPB). */
export function fetchHealthComponent(name: string): Promise<NamedHealthComponent> {
  return apiFetch<HealthComponent>(`/actuator/health/${encodeURIComponent(name)}`, {
    raw: true,
  }).then((component) => ({ ...component, name }));
}

const KNOWN_HEALTH_COMPONENTS = ["hsm", "trustSnapshot"] as const;

/** Loads aggregate health and extracts named components when the actuator exposes them. */
export async function fetchHealthSnapshot(): Promise<{
  aggregate: HealthResponse;
  components: NamedHealthComponent[];
}> {
  const aggregate = await fetchHealth();
  if (aggregate.components && Object.keys(aggregate.components).length > 0) {
    const components = Object.entries(aggregate.components).map(([name, component]) => ({
      name,
      ...component,
    }));
    return { aggregate, components };
  }
  const components = await Promise.all(
    KNOWN_HEALTH_COMPONENTS.map((name) =>
      fetchHealthComponent(name).catch(() => ({ name, status: "UNKNOWN" as const })),
    ),
  );
  return { aggregate, components };
}
