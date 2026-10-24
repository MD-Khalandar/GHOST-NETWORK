import { parsePrincipals, SigningIdentity, type Principal } from "./security.js";
import type { UploadLimits } from "./upload-limits.js";
import { dashboardOrigin as parseDashboardOrigin } from "./dashboard-auth.js";

export interface ServerConfig {
  environment: "development" | "test" | "production";
  host: string;
  port: number;
  authPrincipals: Principal[];
  cloudSigningKeyPem?: string;
  databaseUrl?: string;
  openaiApiKey?: string;
  coordinatorModel?: string;
  aiProvider?: "openai" | "gemini";
  geminiApiKey?: string;
  uploadLimits?: Partial<UploadLimits>;
  aiMaxCallsPerHour?: number;
  dashboardOrigin?: string;
}

export class ConfigValidationError extends Error {
  constructor(readonly issues: readonly string[]) {
    super(`Configuration error: ${issues.join("; ")}`);
    this.name = "ConfigValidationError";
  }
}

export function loadConfig(environment: NodeJS.ProcessEnv = process.env): ServerConfig {
  const issues: string[] = [];
  const uploadLimits: Partial<UploadLimits> = {};
  const positiveLimit = (name: string): number | undefined => {
    if (environment[name] === undefined) return;
    const value = Number(environment[name]);
    if (!Number.isSafeInteger(value) || value < 1 || value > 1000000) issues.push(`${name} must be an integer from 1 to 1000000`);
    return value;
  };
  for (const [name, key] of [["GHOST_UPLOAD_PACKETS_PER_DEVICE_MINUTE", "perDevice"],
    ["GHOST_UPLOAD_PACKETS_PER_IP_MINUTE", "perIp"], ["GHOST_UPLOAD_PACKETS_GLOBAL_MINUTE", "global"]] as const) {
    const value = positiveLimit(name); if (value !== undefined) uploadLimits[key] = value;
  }
  const aiMaxCallsPerHour = positiveLimit("GHOST_AI_MAX_CALLS_PER_HOUR");
  const mode = environment.GHOST_ENV?.trim() || "development";
  let dashboardOrigin: string | undefined;
  if (environment.GHOST_DASHBOARD_ORIGIN !== undefined) {
    try { dashboardOrigin = parseDashboardOrigin(environment.GHOST_DASHBOARD_ORIGIN.trim()); }
    catch { issues.push("GHOST_DASHBOARD_ORIGIN must be an HTTP(S) origin without credentials or a path"); }
  }
  const aiProvider = environment.GHOST_AI_PROVIDER?.trim() || "openai";
  if (!["openai", "gemini"].includes(aiProvider)) issues.push("GHOST_AI_PROVIDER must be openai or gemini");
  if (!["development", "test", "production"].includes(mode)) issues.push("GHOST_ENV must be development, test, or production");
  const port = Number(environment.GHOST_PORT?.trim() || "3000");
  if (!Number.isInteger(port) || port < 1 || port > 65535) issues.push("GHOST_PORT must be an integer from 1 to 65535");
  let authPrincipals: Principal[] = [];
  try { authPrincipals = parsePrincipals(environment.GHOST_AUTH_PRINCIPALS); }
  catch { issues.push("GHOST_AUTH_PRINCIPALS must contain valid, distinct role/token-hash records"); }
  const cloudSigningKeyPem = environment.GHOST_CLOUD_SIGNING_KEY_PEM?.replaceAll("\\n", "\n");
  const databaseUrl = environment.GHOST_DATABASE_URL?.trim();
  if (databaseUrl) {
    try { if (!["postgres:", "postgresql:"].includes(new URL(databaseUrl).protocol)) throw new Error(); }
    catch { issues.push("GHOST_DATABASE_URL must be a PostgreSQL connection URL"); }
    if (!cloudSigningKeyPem) issues.push("GHOST_CLOUD_SIGNING_KEY_PEM is required with database storage");
  }
  if (cloudSigningKeyPem) {
    try { SigningIdentity.fromPem(cloudSigningKeyPem); }
    catch { issues.push("GHOST_CLOUD_SIGNING_KEY_PEM must be a P-256 private key"); }
  }
  if (mode === "production") {
    if (!dashboardOrigin?.startsWith("https:")) issues.push("GHOST_DASHBOARD_ORIGIN must be HTTPS in production");
    if (!cloudSigningKeyPem) issues.push("GHOST_CLOUD_SIGNING_KEY_PEM is required in production");
    if (!databaseUrl) issues.push("GHOST_DATABASE_URL is required in production");
  }
  if (issues.length) throw new ConfigValidationError(issues);
  return { environment: mode as ServerConfig["environment"], host: environment.GHOST_HOST?.trim() || "127.0.0.1", port,
    ...(dashboardOrigin ? { dashboardOrigin } : {}),
    ...(Object.keys(uploadLimits).length ? { uploadLimits } : {}), ...(aiMaxCallsPerHour === undefined ? {} : { aiMaxCallsPerHour }),
    authPrincipals, ...(cloudSigningKeyPem ? { cloudSigningKeyPem } : {}), ...(databaseUrl ? { databaseUrl } : {}),
    ...(environment.OPENAI_API_KEY?.trim() ? { openaiApiKey: environment.OPENAI_API_KEY.trim() } : {}),
    ...(environment.GHOST_AI_PROVIDER?.trim() ? { aiProvider: aiProvider as "openai" | "gemini" } : {}),
    ...(environment.GEMINI_API_KEY?.trim() ? { geminiApiKey: environment.GEMINI_API_KEY.trim() } : {}),
    ...(environment.GHOST_COORDINATOR_MODEL?.trim() ? { coordinatorModel: environment.GHOST_COORDINATOR_MODEL.trim() } : {}) };
}
