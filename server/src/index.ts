import { buildApp } from "./app.js";
import { loadConfig } from "./config.js";
import { createModelProvider } from "./model-provider.js";

export async function start(): Promise<void> {
  const config = loadConfig();
  const modelProvider = createModelProvider(config);
  const app = buildApp(config, modelProvider);
  await app.listen({ host: config.host, port: config.port });
}

start().catch((error: unknown) => {
  const message = error instanceof Error ? error.message : "Unknown startup error";
  console.error(message);
  process.exitCode = 1;
});
