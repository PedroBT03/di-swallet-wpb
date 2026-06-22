export class ApiError extends Error {
  readonly status: number;
  readonly body: unknown;

  constructor(status: number, message: string, body?: unknown) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.body = body;
  }
}

export type ApiFetchOptions = RequestInit & {
  /** When true, do not assume JSON response parsing (e.g. plain text or JWE exports). */
  raw?: boolean;
};

/**
 * Fetch against WPB via the Vite dev proxy (same origin as the UI).
 * Paths should start with `/api`, `/actuator`, `/openid4vp`, or `/openid4vci`.
 */
export async function apiFetch<T>(path: string, options: ApiFetchOptions = {}): Promise<T> {
  const { raw = false, headers: initHeaders, ...init } = options;

  const headers = new Headers(initHeaders);
  if (init.body != null && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }

  const response = await fetch(path, { ...init, headers });

  if (!response.ok) {
    const contentType = response.headers.get("content-type") ?? "";
    let body: unknown = await response.text();
    if (contentType.includes("application/json") && typeof body === "string" && body.length > 0) {
      try {
        body = JSON.parse(body) as unknown;
      } catch {
        /* keep text */
      }
    }
    const message =
      typeof body === "string" && body.length > 0 ? body : `${response.status} ${response.statusText}`;
    throw new ApiError(response.status, message, body);
  }

  if (response.status === 204) {
    return undefined as T;
  }

  const contentType = response.headers.get("content-type") ?? "";
  if (contentType.includes("application/json")) {
    return (await response.json()) as T;
  }

  return (await response.text()) as T;
}

/** WPB base URL for links opened in a new tab (Swagger, etc.). */
export const wpbExternalBase =
  import.meta.env.VITE_WPB_EXTERNAL_BASE ?? "http://localhost:8080";
