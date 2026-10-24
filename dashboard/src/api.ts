export interface SosReport {
  state?: number;
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
export interface Session { id: string; username: string; role: "viewer" | "operator" | "gateway"; csrf_token: string; expires_at_ms: number }

export class ApiError extends Error {
  constructor(readonly status: number, readonly code?: string) {
    const detail = code === "INVALID_CREDENTIALS" ? "Username or password not accepted." :
      code === "INVALID_LOGIN" ? "Enter your username and password." :
      code === "LOGIN_BUSY" ? "Too many sign-in attempts. Wait a few minutes and try again." :
      code === "CSRF_REJECTED" ? "Your session changed. Refresh the page and try again." :
      code === "AUTH_UNAVAILABLE" ? "Sign-in is temporarily unavailable. Check with the server administrator." :
      code === "MODEL_UNAVAILABLE" ? "AI coordination is not configured on the server." :
      code === "PROPOSAL_STALE_OR_QUANTITY_UNKNOWN" ? "The proposal changed, expired, or needs a known supply quantity. Refresh before approving." :
      code === "RESOURCE_ALREADY_COMMITTED" ? "This resource is already committed. Refresh availability." :
      code === "RUN_COOLDOWN" ? "Wait a moment before requesting another AI run." : null;
    super(detail ?? (status === 401 ? "Your session expired. Sign in again." :
      status === 403 ? "Your account cannot perform this action." :
      status === 503 ? "Server storage is unavailable. Try again shortly." : "The server couldn't complete this action. Try again."));
  }
}

export async function get<T>(path: string, signal: AbortSignal): Promise<T> {
  return request<T>(path, signal);
}
export async function post<T>(path: string, csrfToken: string, signal: AbortSignal): Promise<T> {
  return request<T>(path, signal, "POST", csrfToken);
}
export async function login(username: string, password: string, signal: AbortSignal): Promise<Session> {
  return request<Session>("auth/login", signal, "POST", undefined, { username, password });
}
export async function logout(csrfToken: string, signal: AbortSignal): Promise<void> {
  return post<void>("auth/logout", csrfToken, signal);
}
async function request<T>(path: string, signal: AbortSignal, method = "GET", csrfToken?: string, body?: object): Promise<T> {
  const response = await fetch(`/v1/${path}`, {
    method,
    headers: { "X-Ghost-Desk": "1", ...(csrfToken ? { "X-CSRF-Token": csrfToken } : {}), ...(body ? { "Content-Type": "application/json" } : {}) },
    ...(body ? { body: JSON.stringify(body) } : {}),
    cache: "no-store", credentials: "same-origin", redirect: "error",
    signal: AbortSignal.any([signal, AbortSignal.timeout(15_000)]),
  });
  if (!response.ok) {
    const failure = await response.json().catch(() => null);
    throw new ApiError(response.status, typeof failure?.code === "string" ? failure.code : undefined);
  }
  return response.status === 204 ? undefined as T : response.json() as Promise<T>;
}
