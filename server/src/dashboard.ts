import fastifyStatic from "@fastify/static";
import type { FastifyInstance } from "fastify";
import { fileURLToPath } from "node:url";

// One origin for the app and API; no credentials or report data in static files.
export function registerDashboard(app: FastifyInstance): void {
  app.register(fastifyStatic, {
    root: fileURLToPath(new URL("../../dashboard/dist/", import.meta.url)),
    prefix: "/dashboard/",
    wildcard: true,
    dotfiles: "deny",
    setHeaders(response) {
      response.header("Cache-Control", "no-store");
      response.header("X-Content-Type-Options", "nosniff");
      response.header("Referrer-Policy", "no-referrer");
      response.header("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; base-uri 'none'; frame-ancestors 'none'; form-action 'self'");
    },
  });
  app.get("/dashboard", (_request, reply) => reply.redirect("/dashboard/"));
}
