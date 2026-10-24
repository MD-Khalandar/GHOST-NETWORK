import { buildApp } from "./app.js";
import { ConfigValidationError, loadConfig } from "./config.js";
import { postgresDatabase } from "./database.js";
import { OpenAIProposalModel } from "./coordinator/openai-model.js";

export async function start(): Promise<void> {
  const config = loadConfig();
  const app = buildApp({ principals: config.authPrincipals, cloudSigningKeyPem: config.cloudSigningKeyPem,
    proposalModel: new OpenAIProposalModel({ apiKey: config.openaiApiKey, model: config.coordinatorModel }),
    ...(config.databaseUrl ? { database: postgresDatabase(config.databaseUrl) } : {}) });
  try { await app.listen({ host: config.host, port: config.port }); }
  catch (error) { await app.close(); throw error; }
  const shutdown = () => { void app.close().catch(() => { process.exitCode = 1; }); };
  process.once("SIGTERM", shutdown);
  process.once("SIGINT", shutdown);
}

start().catch((error: unknown) => {
  const message = error instanceof ConfigValidationError ? error.message : "Server startup failed. Check database connectivity and configuration.";
  console.error(message);
  process.exitCode = 1;
});
