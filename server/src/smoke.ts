import assert from "node:assert/strict";
import { buildApp } from "./app.js";
import { loadConfig } from "./config.js";

const config = loadConfig();
const app = buildApp({ principals: config.authPrincipals, cloudSigningKeyPem: config.cloudSigningKeyPem });

try {
  const response = await app.inject({ method: "GET", url: "/v1/health" });
  assert.equal(response.statusCode, 200);
  console.log(`${response.statusCode} ${response.body}`);
} finally {
  await app.close();
}
