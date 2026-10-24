export interface SosReport {
  request_id: string;
  category: number;
  description: string;
  people_count: number | null;
  coarse_area: string | null;
  landmark: string | null;
  created_at_ms: number;
  expires_at_ms: number;
  received_at_ms: number;
  cloud_revision: number;
}
export interface SosPage { items: SosReport[]; next_before: number | null }
export interface Session { id: string; role: "viewer" | "operator" | "gateway" }

export class ApiError extends Error {
  constructor(readonly status: number) {
    super(status === 401 ? "Access token not accepted. Sign in again." :
      status === 403 ? "Use a viewer or operator token. Gateway tokens cannot open this desk." :
      status === 503 ? "Server storage is unavailable. Try again shortly." : "The server couldn't load requests. Try again.");
  }
}

export async function get<T>(path: string, token: string, signal: AbortSignal): Promise<T> {
  const response = await fetch(`/v1/${path}`, {
    headers: { Authorization: `Bearer ${token}` },
    cache: "no-store", credentials: "omit", redirect: "error",
    signal: AbortSignal.any([signal, AbortSignal.timeout(15_000)]),
  });
  if (!response.ok) throw new ApiError(response.status);
  return response.json() as Promise<T>;
}
