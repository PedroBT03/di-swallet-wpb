import type { HealthResponse } from "../types/health";
import { apiFetch } from "./client";

export function fetchHealth(): Promise<HealthResponse> {
  return apiFetch<HealthResponse>("/actuator/health", { raw: true });
}
