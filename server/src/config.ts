import { parsePrincipals, SigningIdentity, type Principal } from "./security.js";

export interface ServerConfig {
  environment: "development" | "test" | "production";
  host: string;
  port: number;
  authPrincipals: Principal[];
  cloudSigningKeyPem?: string;
}

export class ConfigValidationError extends Error {
  constructor(readonly issues: readonly string[]) {
    super(`Configuration error: ${issues.join("; ")}`);
    this.name = "ConfigValidationError";
  }
}

export function loadConfig(environment: NodeJS.ProcessEnv = process.env): ServerConfig {
  const issues: string[] = [];
  const mode = environment.GHOST_ENV?.trim() || "development";
  if (!["development", "test", "production"].includes(mode)) issues.push("GHOST_ENV must be development, test, or production");
  const port = Number(environment.GHOST_PORT?.trim() || "3000");
  if (!Number.isInteger(port) || port < 1 || port > 65535) issues.push("GHOST_PORT must be an integer from 1 to 65535");
  let authPrincipals: Principal[] = [];
  try { authPrincipals = parsePrincipals(environment.GHOST_AUTH_PRINCIPALS); }
  catch { issues.push("GHOST_AUTH_PRINCIPALS must contain valid, distinct role/token-hash records"); }
  const cloudSigningKeyPem = environment.GHOST_CLOUD_SIGNING_KEY_PEM?.replaceAll("\\n", "\n");
  if (cloudSigningKeyPem) {
    try { SigningIdentity.fromPem(cloudSigningKeyPem); }
    catch { issues.push("GHOST_CLOUD_SIGNING_KEY_PEM must be a P-256 private key"); }
  }
  if (mode === "production") {
    if (!authPrincipals.length) issues.push("GHOST_AUTH_PRINCIPALS is required in production");
    if (!cloudSigningKeyPem) issues.push("GHOST_CLOUD_SIGNING_KEY_PEM is required in production");
  }
  if (issues.length) throw new ConfigValidationError(issues);
  return { environment: mode as ServerConfig["environment"], host: environment.GHOST_HOST?.trim() || "127.0.0.1", port,
    authPrincipals, ...(cloudSigningKeyPem ? { cloudSigningKeyPem } : {}) };
}
