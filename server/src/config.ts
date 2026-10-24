import { parsePrincipals, SigningIdentity, type Principal } from "./security.js";

export const CONTRACT_VERSION = "ghost.contracts.v1" as const;

export type ServerEnvironment = "development" | "test" | "production";
export type ModelProviderKind = "fake" | "openai";

export interface ServerConfig {
  environment: ServerEnvironment;
  host: string;
  port: number;
  databaseUrl: string;
  modelProvider: ModelProviderKind;
  model: string;
  openAiApiKey?: string;
  authPrincipals: Principal[];
  cloudSigningKeyPem?: string;
}

export class ConfigValidationError extends Error {
  readonly issues: readonly string[];

  constructor(issues: readonly string[]) {
    super(`Configuration error: ${issues.join("; ")}`);
    this.name = "ConfigValidationError";
    this.issues = issues;
  }
}

function requiredValue(name: string, environment: NodeJS.ProcessEnv, issues: string[]): string {
  const value = environment[name]?.trim();
  if (!value) {
    issues.push(`${name} is required`);
    return "";
  }
  return value;
}

function parseEnvironment(value: string | undefined, issues: string[]): ServerEnvironment {
  const environment = value?.trim() || "development";
  if (environment === "development" || environment === "test" || environment === "production") {
    return environment;
  }
  issues.push("GHOST_ENV must be development, test, or production");
  return "development";
}

function parsePort(value: string | undefined, issues: string[]): number {
  const raw = value?.trim() || "3000";
  const port = Number(raw);
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    issues.push("GHOST_PORT must be an integer from 1 to 65535");
    return 3000;
  }
  return port;
}

function parseProvider(value: string | undefined, issues: string[]): ModelProviderKind {
  const provider = value?.trim() || "fake";
  if (provider === "fake" || provider === "openai") {
    return provider;
  }
  issues.push("GHOST_MODEL_PROVIDER must be fake or openai");
  return "fake";
}

function validateDatabaseUrl(value: string, issues: string[]): void {
  try {
    const url = new URL(value);
    if (url.protocol !== "postgres:" && url.protocol !== "postgresql:") {
      issues.push("GHOST_DATABASE_URL must use postgres:// or postgresql://");
    }
  } catch {
    issues.push("GHOST_DATABASE_URL must be a valid postgres:// or postgresql:// URL");
  }
}

export function loadConfig(environment: NodeJS.ProcessEnv = process.env): ServerConfig {
  const issues: string[] = [];
  const databaseUrl = requiredValue("GHOST_DATABASE_URL", environment, issues);
  const model = requiredValue("GHOST_MODEL", environment, issues);
  const modelProvider = parseProvider(environment.GHOST_MODEL_PROVIDER, issues);
  const openAiApiKey = environment.GHOST_OPENAI_API_KEY?.trim();
  let authPrincipals: Principal[] = [];
  try { authPrincipals = parsePrincipals(environment.GHOST_AUTH_PRINCIPALS); }
  catch { issues.push("GHOST_AUTH_PRINCIPALS must contain valid, distinct role/token-hash records"); }
  const cloudSigningKeyPem = environment.GHOST_CLOUD_SIGNING_KEY_PEM?.replaceAll("\\n", "\n");
  if (cloudSigningKeyPem) {
    try { SigningIdentity.fromPem(cloudSigningKeyPem); }
    catch { issues.push("GHOST_CLOUD_SIGNING_KEY_PEM must be a P-256 private key"); }
  }
  if (environment.GHOST_ENV === "production") {
    if (!authPrincipals.length) issues.push("GHOST_AUTH_PRINCIPALS is required in production");
    if (!cloudSigningKeyPem) issues.push("GHOST_CLOUD_SIGNING_KEY_PEM is required in production");
  }

  if (databaseUrl) {
    validateDatabaseUrl(databaseUrl, issues);
  }
  if (modelProvider === "openai" && !openAiApiKey) {
    issues.push("GHOST_OPENAI_API_KEY is required when GHOST_MODEL_PROVIDER=openai");
  }

  const config: ServerConfig = {
    environment: parseEnvironment(environment.GHOST_ENV, issues),
    host: environment.GHOST_HOST?.trim() || "127.0.0.1",
    port: parsePort(environment.GHOST_PORT, issues),
    databaseUrl,
    modelProvider,
    model,
    authPrincipals,
    ...(cloudSigningKeyPem ? { cloudSigningKeyPem } : {}),
    ...(openAiApiKey ? { openAiApiKey } : {}),
  };

  if (issues.length > 0) {
    throw new ConfigValidationError(issues);
  }
  return config;
}
