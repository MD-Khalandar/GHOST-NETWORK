export interface SosReport {
  request_id: string;
  category: number;
  description: string;
  people_count: number | null;
  coarse_area: string | null;
  landmark: string | null;
  latitude?: number | null;
  longitude?: number | null;
  created_at_ms: number;
  expires_at_ms: number;
  received_at_ms: number;
  cloud_revision: number;
}
export interface SosPage { items: SosReport[]; next_before: number | null }
export interface Session { id: string; role: "viewer" | "operator" | "gateway" }

export class ApiError extends Error {
  constructor(readonly status: number, readonly code?: string) {
    const detail = code === "MODEL_UNAVAILABLE" ? "AI coordination is not configured on the server." :
      code === "RUN_COOLDOWN" ? "Wait a moment before requesting another AI run." : null;
    super(detail ?? (status === 401 ? "Access token not accepted. Sign in again." :
      status === 403 ? "Use a viewer or operator token. Gateway tokens cannot open this desk." :
      status === 503 ? "Server storage is unavailable. Try again shortly." : "The server couldn't complete this action. Try again."));
  }
}

export async function get<T>(path: string, token: string, signal: AbortSignal): Promise<T> {
  return request<T>(path, token, signal);
}
export async function post<T>(path: string, token: string, signal: AbortSignal): Promise<T> {
  return request<T>(path, token, signal, "POST");
}
async function request<T>(path: string, token: string, signal: AbortSignal, method = "GET"): Promise<T> {
  const response = await fetch(`/v1/${path}`, {
    method,
    headers: { Authorization: `Bearer ${token}` },
    cache: "no-store", credentials: "omit", redirect: "error",
    signal: AbortSignal.any([signal, AbortSignal.timeout(15_000)]),
  });
  if (!response.ok) {
    const failure = await response.json().catch(() => null);
    throw new ApiError(response.status, typeof failure?.code === "string" ? failure.code : undefined);
  }
  return response.json() as Promise<T>;
}
