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

/** Loads aggregate health plus known contributor endpoints (details are often hidden on /health). */
export async function fetchHealthSnapshot(): Promise<{
  aggregate: HealthResponse;
  components: NamedHealthComponent[];
}> {
  const [aggregate, ...components] = await Promise.all([
    fetchHealth(),
    ...KNOWN_HEALTH_COMPONENTS.map((name) =>
      fetchHealthComponent(name).catch(() => ({ name, status: "UNKNOWN" as const })),
    ),
  ]);
  return { aggregate, components };
}
