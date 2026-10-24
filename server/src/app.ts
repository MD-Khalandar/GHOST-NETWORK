import Fastify, { type FastifyInstance } from "fastify";
import { CONTRACT_VERSION, type ServerConfig } from "./config.js";
import type { ModelProvider } from "./model-provider.js";

export function buildApp(config: ServerConfig, modelProvider: ModelProvider): FastifyInstance {
  const app = Fastify({ logger: false });

  app.get("/v1/health", async (_request, reply) => {
    return reply.code(200).send({
      status: "ok",
      contract_version: CONTRACT_VERSION,
      database: "not_checked",
      model_provider: "configured",
    });
  });

  app.addHook("onClose", async () => {
    void config;
    void modelProvider;
  });

  return app;
}
