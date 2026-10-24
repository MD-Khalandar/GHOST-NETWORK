import Fastify, { type FastifyInstance } from "fastify";
import { authenticate, SigningIdentity, type Principal, type Role } from "./security.js";
import { PacketError, validatePacket, type AuthorityLookup, type WirePacket } from "./protocol.js";

export interface AppSecurity { principals?: readonly Principal[]; cloudSigningKeyPem?: string; lookup?: AuthorityLookup; now?: () => number }

export function buildApp(security: AppSecurity = {}): FastifyInstance {
  const app = Fastify({ logger: false, bodyLimit: 2400000 });
  app.setErrorHandler((error, _request, reply) => {
    const status = typeof (error as { statusCode?: number }).statusCode === "number"
      ? (error as { statusCode: number }).statusCode : 500;
    return reply.code(status >= 400 && status < 600 ? status : 500).send({
      code: status === 413 ? "PAYLOAD_TOO_LARGE" : status < 500 ? "INVALID_REQUEST" : "INTERNAL_ERROR",
      message: "Request could not be processed", retryable: false,
    });
  });
  // The signer is only available on the server. No signing HTTP endpoint exists.
  if (security.cloudSigningKeyPem) SigningIdentity.fromPem(security.cloudSigningKeyPem);
  const principals = security.principals ?? [];
  const routes: Array<{ method: "GET" | "POST"; url: string; roles: Role[] }> = [
    { method: "GET", url: "/v1/auth/session", roles: ["gateway", "viewer", "operator"] },
    ...["/v1/dashboard/sos", "/v1/dashboard/sos/:requestId", "/v1/dashboard/helpers", "/v1/dashboard/offers", "/v1/dashboard/runs"]
      .map(url => ({ method: "GET" as const, url, roles: ["viewer", "operator"] as Role[] })),
    ...["approve", "reject"].map(action => ({ method: "POST" as const, url: `/v1/dashboard/proposals/:proposalId/${action}`, roles: ["operator"] as Role[] })),
    { method: "POST", url: "/v1/sync/upload", roles: ["gateway"] },
    { method: "GET", url: "/v1/sync/download", roles: ["gateway"] },
    { method: "POST", url: "/v1/sync/download-ack", roles: ["gateway"] },
  ];
  for (const route of routes) {
    app.route({ ...route, onRequest: async (request, reply) => {
      const principal = authenticate(request.headers.authorization, principals);
      if (!principal) return reply.code(401).send({ code: "UNAUTHENTICATED", message: "Authentication required", retryable: false });
      if (!route.roles.includes(principal.role)) return reply.code(403).send({ code: "FORBIDDEN", message: "Role is not authorized", retryable: false });
    }, handler: async (request, reply) => {
      if (route.url === "/v1/auth/session") {
        const p = authenticate(request.headers.authorization, principals)!;
        return { id: p.id, role: p.role, ...(p.gateway_node_id ? { gateway_node_id: p.gateway_node_id } : {}) };
      }
      if (route.url === "/v1/sync/upload") {
        const body = request.body as { idempotency_key?: unknown; packets?: unknown; receipts?: unknown } | null;
        if (!body || typeof body.idempotency_key !== "string" || !body.idempotency_key.length || body.idempotency_key.length > 128 ||
            !Array.isArray(body.packets) || !body.packets.length || body.packets.length > 50 ||
            body.receipts !== undefined) {
          return reply.code(400).send({ code: "INVALID_UPLOAD", message: "Use bounded signed packets; unsigned receipts are forbidden", retryable: false });
        }
        try {
          for (const packet of body.packets) validatePacket(packet as WirePacket, {
            nowMs: (security.now ?? Date.now)(), forCloud: true, lookup: security.lookup,
          });
        } catch (error) {
          const code = error instanceof PacketError ? error.code : "MALFORMED_PACKET";
          return reply.code(400).send({ code, message: "Packet rejected", retryable: false });
        }
      }
      // Trust checks do not imply that a request has been stored or approved.
      return reply.code(501).send({ code: "NOT_IMPLEMENTED", message: "Persistent operation is not implemented", retryable: false });
    } });
  }

  app.get("/v1/health", async (_request, reply) => {
    return reply.code(200).send({ status: "ok" });
  });

  return app;
}
