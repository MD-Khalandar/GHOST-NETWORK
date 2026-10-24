import { AssistanceService, AssistanceConflict } from "./assistance.js";
import { DashboardAuth, DeskAuthError, deskSessionResponse, type DeskSession } from "./dashboard-auth.js";
import Fastify, { type FastifyInstance } from "fastify";
import { authenticate, SigningIdentity, type Principal, type Role } from "./security.js";
import { PacketError, validatePacket, type AuthorityLookup, type WirePacket } from "./protocol.js";
import { initializeDatabase, type Database } from "./database.js";
import { SosStore, UploadConflict } from "./sos-store.js";
import { registerDashboard } from "./dashboard.js";
import { listResources } from "./resource-store.js";
import { initializeCoordinator } from "./coordinator/store.js";
import { CoordinatorWorker } from "./coordinator/worker.js";
import { OpenAIProposalModel } from "./coordinator/openai-model.js";
import { CoordinatorFailure, UUID, type ProposalModel } from "./coordinator/types.js";
import { GatewayEnrollment, initializeEnrollment, registerEnrollment } from "./enrollment.js";
import { initializeUploadLimits, UploadLimiter, UploadThrottled, type UploadLimits } from "./upload-limits.js";

export interface AppSecurity { principals?: readonly Principal[]; cloudSigningKeyPem?: string; lookup?: AuthorityLookup; now?: () => number; database?: Database;
  proposalModel?: ProposalModel; uploadLimits?: Partial<UploadLimits>; aiMaxCallsPerHour?: number; dashboardOrigin?: string }

export function buildApp(security: AppSecurity = {}): FastifyInstance {
  const app = Fastify({ logger: false, bodyLimit: 2400000 });
  registerDashboard(app);
  app.setErrorHandler((error, _request, reply) => {
    const status = typeof (error as { statusCode?: number }).statusCode === "number"
      ? (error as { statusCode: number }).statusCode : 500;
    return reply.code(status >= 400 && status < 600 ? status : 500).send({
      code: status === 413 ? "PAYLOAD_TOO_LARGE" : status < 500 ? "INVALID_REQUEST" : "INTERNAL_ERROR",
      message: "Request could not be processed", retryable: false,
    });
  });
  // The signer stays on the server. Enrollment signs fixed, domain-separated messages only.
  const signer = security.cloudSigningKeyPem ? SigningIdentity.fromPem(security.cloudSigningKeyPem) : undefined;
  if (security.database && !signer) throw new Error("Cloud signing key is required with storage");
  const assistance = security.database && signer ? new AssistanceService(security.database,signer,security.now) : undefined;
  const store = security.database && signer ? new SosStore(security.database, signer, security.now) : undefined;
  const enrollment = security.database && signer ? new GatewayEnrollment(security.database, signer, security.now) : undefined;
  const uploads = security.database ? new UploadLimiter(security.database, security.uploadLimits, security.now) : undefined;
  registerEnrollment(app, enrollment);
  const desk = security.database ? new DashboardAuth(security.database, security.dashboardOrigin, security.now) : undefined;
  const browserSessions = new WeakMap<object, DeskSession>();
  for (const action of ["login", "logout"] as const) {
    app.post(`/v1/auth/${action}`, { bodyLimit: 4096 }, async (request, reply) => {
      reply.header("Cache-Control", "no-store");
      if (!desk) return reply.code(503).send({ code: "AUTH_UNAVAILABLE", retryable: true });
      try {
        if (action === "login") return deskSessionResponse(await desk.login(request, reply));
        await desk.logout(request, reply);
        return reply.code(204).send();
      } catch (error) {
        if (error instanceof DeskAuthError) {
          if (error.retryAfter) reply.header("Retry-After", String(error.retryAfter));
          return reply.code(error.status).send({ code: error.code, retryable: error.status === 429 });
        }
        return reply.code(503).send({ code: "AUTH_UNAVAILABLE", retryable: true });
      }
    });
  }
  const authorized = new WeakMap<object, Principal>();
  const coordinator = security.database ? new CoordinatorWorker(security.database,
    security.proposalModel ?? new OpenAIProposalModel(), security.now, security.aiMaxCallsPerHour) : undefined;
  if (security.database) {
    const database = security.database;
    app.addHook("onReady", async () => { await initializeDatabase(database); await initializeEnrollment(database); await initializeUploadLimits(database); await initializeCoordinator(database); coordinator!.start(); });
    app.addHook("preClose", async () => { await coordinator!.stop(); });
    app.addHook("onClose", () => database.close());
  }
  const principals = security.principals ?? [];
  const routes: Array<{ method: "GET" | "POST"; url: string; roles: Role[] }> = [
    { method: "GET", url: "/v1/auth/session", roles: ["gateway", "viewer", "operator"] },
    ...["/v1/dashboard/sos", "/v1/dashboard/sos/:requestId", "/v1/dashboard/helpers", "/v1/dashboard/offers", "/v1/dashboard/runs", "/v1/dashboard/proposals", "/v1/dashboard/assistance"]
      .map(url => ({ method: "GET" as const, url, roles: ["viewer", "operator"] as Role[] })),
    { method: "POST", url: "/v1/dashboard/sos/:requestId/coordinate", roles: ["operator"] },
    ...["approve", "reject"].map(action => ({ method: "POST" as const, url: `/v1/dashboard/proposals/:proposalId/${action}`, roles: ["operator"] as Role[] })),
    { method: "POST", url: "/v1/sync/upload", roles: ["gateway"] },
    { method: "GET", url: "/v1/sync/download", roles: ["gateway"] },
    { method: "POST", url: "/v1/sync/download-ack", roles: ["gateway"] },
  ];
  for (const route of routes) {
    app.route({ ...route, onRequest: async (request, reply) => {
      reply.header("Cache-Control", "no-store");
      let principal = authenticate(request.headers.authorization, principals);
      try {
        principal ??= await enrollment?.authenticate(request.headers.authorization);
        // Browser cookies grant dashboard access only. Phone sync still requires its bearer credential.
        if (!request.headers.authorization && (route.url.startsWith("/v1/dashboard/") || route.url === "/v1/auth/session")) {
          const session = await desk?.authenticate(request);
          if (session) {
            principal = session.principal;
            browserSessions.set(request, session);
            if (route.method === "POST") desk!.checkMutation(request, session);
          }
        }
      }
      catch (error) {
        if (error instanceof DeskAuthError) return reply.code(error.status).send({ code: error.code, retryable: false });
        return reply.code(503).send({ code: "AUTH_UNAVAILABLE", retryable: true });
      }
      if (!principal) return reply.code(401).send({ code: "UNAUTHENTICATED", message: "Authentication required", retryable: false });
      if (!route.roles.includes(principal.role)) return reply.code(403).send({ code: "FORBIDDEN", message: "Role is not authorized", retryable: false });
      authorized.set(request, principal);
    }, handler: async (request, reply) => {
      if (route.url === "/v1/dashboard/assistance") {
        if (Object.keys(request.query as object).length) return reply.code(400).send({code:"INVALID_QUERY",retryable:false});
        if (!assistance) return reply.code(503).send({code:"STORAGE_UNAVAILABLE",retryable:true});
        try { return await assistance.list(); }
        catch { return reply.code(503).send({code:"STORAGE_UNAVAILABLE",retryable:true}); }
      }
      if (route.url.match(/\/proposals\/[^/]+\/(approve|reject)$/)) {
        const id = (request.params as {proposalId:string}).proposalId;
        if (!UUID.test(id) || Object.keys(request.query as object).length || request.body!==undefined &&
          (!request.body || typeof request.body!=="object" || Array.isArray(request.body) || Object.keys(request.body).length))
          return reply.code(400).send({code:"INVALID_REQUEST",retryable:false});
        if (!assistance) return reply.code(503).send({code:"STORAGE_UNAVAILABLE",retryable:true});
        try { return await assistance.review(id,route.url.endsWith("/approve"),authorized.get(request)!.id); }
        catch(error) { if(error instanceof AssistanceConflict) return reply.code(409).send({code:error.code,retryable:false});
          return reply.code(503).send({code:"STORAGE_UNAVAILABLE",retryable:true}); }
      }
      if (route.url === "/v1/sync/download") {
        const query=request.query as Record<string,string>;
        if(Object.keys(query).some(k=>!["cursor","limit"].includes(k)) || !/^(0|[1-9][0-9]{0,14})$/.test(query.cursor??"0") ||
          !/^[1-9][0-9]?$/.test(query.limit??"30") || Number(query.limit??30)>50)
          return reply.code(400).send({code:"INVALID_QUERY",retryable:false});
        if(!assistance) return reply.code(503).send({code:"STORAGE_UNAVAILABLE",retryable:true});
        try { return await assistance.download(Number(query.cursor??0),Number(query.limit??30)); }
        catch { return reply.code(503).send({code:"STORAGE_UNAVAILABLE",retryable:true}); }
      }
      if (route.url === "/v1/sync/download-ack") {
        const body=request.body as {cursor?:unknown;packet_ids?:unknown};
        if(!body || Object.keys(request.query as object).length || Object.keys(body).some(k=>!["cursor","packet_ids"].includes(k)) ||
          typeof body.cursor!=="string" || !/^[0-9]{1,15}$/.test(body.cursor) || !Array.isArray(body.packet_ids) ||
          body.packet_ids.length>50 || body.packet_ids.some(id=>typeof id!=="string" || !UUID.test(id)))
          return reply.code(400).send({code:"INVALID_REQUEST",retryable:false});
        if(!assistance)return reply.code(503).send({code:"STORAGE_UNAVAILABLE",retryable:true});
        try{await assistance.acknowledge(authorized.get(request)!.id,body.cursor,body.packet_ids as string[]);}
        catch(error){if(error instanceof AssistanceConflict)return reply.code(400).send({code:error.code,retryable:false});
          return reply.code(503).send({code:"STORAGE_UNAVAILABLE",retryable:true});}
        // A gateway acknowledgement only records transport persistence, never owner acceptance.
        return reply.code(204).send();
      }
      if (route.url === "/v1/auth/session") {
        const session = browserSessions.get(request);
        if (session) return deskSessionResponse(session);
        const p = authorized.get(request)!;
        return { id: p.id, role: p.role, ...(p.gateway_node_id ? { gateway_node_id: p.gateway_node_id } : {}) };
      }
      if (route.url === "/v1/dashboard/runs" || route.url === "/v1/dashboard/proposals" || route.url.endsWith("/coordinate")) {
        const id = (request.params as { requestId?: string }).requestId;
        const query = request.query as Record<string, unknown>;
        const proposals = route.url === "/v1/dashboard/proposals";
        const positive = (v: unknown): v is string => typeof v === "string" && /^[1-9][0-9]{0,15}$/.test(v) && Number.isSafeInteger(Number(v));
        const cursor = typeof query.before === "string" ? query.before.split(".") : [];
        if ((proposals ? Object.keys(query).some(k => !["limit", "before", "request_id"].includes(k)) ||
          query.limit !== undefined && (!positive(query.limit) || Number(query.limit) > 100) ||
          query.before !== undefined && (cursor.length !== 2 || !positive(cursor[0]) || !UUID.test(cursor[1] ?? "")) ||
          query.request_id !== undefined && (typeof query.request_id !== "string" || !UUID.test(query.request_id)) : Object.keys(query).length > 0) || route.method === "POST" &&
          (!UUID.test(id ?? "") || request.body !== undefined && (!request.body || typeof request.body !== "object" || Array.isArray(request.body) || Object.keys(request.body).length)))
          return reply.code(400).send({ code: "INVALID_REQUEST", message: "Use a request ID and no extra parameters", retryable: false });
        if (!coordinator) return reply.code(503).send({ code: "STORAGE_UNAVAILABLE", message: "Coordination storage is not configured", retryable: true });
        try {
          if (route.url === "/v1/dashboard/runs") return await coordinator.status();
          if (proposals) return await coordinator.store.reviewPage((security.now ?? Date.now)(), Number(query.limit ?? 30),
            query.before === undefined ? undefined : { createdAt: Number(cursor[0]), id: cursor[1] }, query.request_id as string | undefined);
          if (!coordinator.model.available) return reply.code(503).send({ code: "MODEL_UNAVAILABLE", message: "AI coordination is not configured", retryable: false });
          if (!await coordinator.enqueue(id!)) return reply.code(429).send({ code: "RUN_COOLDOWN", message: "Wait briefly before retrying this request", retryable: true });
          return reply.code(202).send({ request_id: id, status: "queued" });
        } catch (error) {
          if (error instanceof CoordinatorFailure && error.code === "REQUEST_UNAVAILABLE")
            return reply.code(404).send({ code: error.code, message: "Request is unavailable or expired", retryable: false });
          return reply.code(503).send({ code: "STORAGE_UNAVAILABLE", message: "Coordination is temporarily unavailable", retryable: true });
        }
      }
      if (["/v1/dashboard/sos", "/v1/dashboard/sos/:requestId", "/v1/dashboard/helpers", "/v1/dashboard/offers"].includes(route.url)) {
        const query = request.query as Record<string, unknown>;
        const positiveInteger = (value: unknown): value is string => typeof value === "string" &&
          /^[1-9][0-9]{0,15}$/.test(value) && Number.isSafeInteger(Number(value));
        const detail = route.url.endsWith(":requestId");
        const id = (request.params as { requestId?: string }).requestId;
        if (detail ? !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(id ?? "") :
          Object.keys(query).some(key => !["limit", "before"].includes(key)) ||
          query.limit !== undefined && (!positiveInteger(query.limit) || Number(query.limit) > 100) ||
          query.before !== undefined && !positiveInteger(query.before)) {
          return reply.code(400).send({ code: "INVALID_QUERY", message: "Use a valid request ID or pagination parameters", retryable: false });
        }
        if (!store) return reply.code(503).send({ code: "STORAGE_UNAVAILABLE", message: "SOS storage is not configured", retryable: true });
        try {
          if (detail) {
            const report = await store.find(id!);
            return report ?? reply.code(404).send({ code: "NOT_FOUND", message: "Request not found", retryable: false });
          }
          if (route.url.endsWith("helpers") || route.url.endsWith("offers"))
            return await listResources(security.database!, route.url.endsWith("helpers") ? 3 : 5,
              Number(query.limit ?? 30), query.before === undefined ? undefined : Number(query.before), (security.now??Date.now)());
          return await store.list(Number(query.limit ?? 30), query.before === undefined ? undefined : Number(query.before));
        } catch {
          return reply.code(503).send({ code: "STORAGE_UNAVAILABLE", message: "Saved requests are temporarily unavailable", retryable: true });
        }
      }
      if (route.url === "/v1/sync/upload") {
        const body = request.body as { idempotency_key?: unknown; packets?: unknown; receipts?: unknown } | null;
        if (!body || typeof body.idempotency_key !== "string" || !body.idempotency_key.length || body.idempotency_key.length > 128 ||
            !Array.isArray(body.packets) || !body.packets.length || body.packets.length > 50 ||
            body.receipts !== undefined) {
          return reply.code(400).send({ code: "INVALID_UPLOAD", message: "Use bounded signed packets; unsigned receipts are forbidden", retryable: false });
        }
        try {
          if (uploads) await uploads.admit(authorized.get(request)!.id, request.ip, body.packets.length);
          const packets = body.packets.map(packet => {
            const verified = validatePacket(packet as WirePacket, {
              nowMs: (security.now ?? Date.now)(), forCloud: true, lookup: security.lookup, trustedCloudNodeId:signer?.nodeId,
            });
            if (![1, 2, 3, 5, 6, 17].includes(verified.envelope.kind)) throw new PacketError("UNSUPPORTED_KIND");
            if (verified.envelope.kind === 2 && (![5, 6].includes(verified.payload.state) || !verified.payload.original_sos_envelope?.length)) throw new PacketError("INVALID_PAYLOAD");
            return verified;
          });
          if (!store) return reply.code(503).send({ code: "STORAGE_UNAVAILABLE", message: "SOS storage is not configured", retryable: true });
          const principal = authorized.get(request)!;
          const response = await store.upload(principal.id, body.idempotency_key, packets);
          coordinator?.wake();
          return response;
        } catch (error) {
          if (error instanceof UploadThrottled) return reply.header("Retry-After", String(error.retryAfterSeconds)).code(429)
            .send({ code: "UPLOAD_BUSY", retryable: true });
          if (error instanceof PacketError) return reply.code(400).send({ code: error.code, message: "Packet rejected", retryable: false });
          if (error instanceof AssistanceConflict) return reply.code(409).send({code:error.code,retryable:false});
          if (error instanceof UploadConflict) return reply.code(409).send({ code: error.code, message: "Upload conflicts with previously saved content", retryable: false });
          return reply.code(503).send({ code: "STORAGE_UNAVAILABLE", message: "SOS upload temporarily unavailable", retryable: true });
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
