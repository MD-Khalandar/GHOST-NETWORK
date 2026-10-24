import Fastify, { type FastifyInstance } from "fastify";
import { CONTRACT_VERSION } from "./config.js";

export function buildApp(): FastifyInstance {
  const app = Fastify({ logger: false });

  app.get("/v1/health", async (_request, reply) => {
    return reply.code(200).send({
      status: "ok",
      contract_version: CONTRACT_VERSION,
      database: "not_checked",
      model_provider: "configured",
    });
  });

  return app;
}
