import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { parseDocument } from "yaml";

// The former Ruby check runs with the server tests, using the same toolchain.
// This checks structure and references, not full OpenAPI conformance.
test("shared API has valid security, tags, operation IDs and internal references", () => {
  const document = parseDocument(readFileSync(new URL("../../contracts/api/cloud-api-v1.yaml", import.meta.url), "utf8"));
  assert.deepEqual(document.errors, []);
  const api = document.toJS();
  assert.equal(api.openapi, "3.1.0");
  const schemes = api.components.securitySchemes;
  const checkSecurity = (requirements: unknown) => {
    assert.ok(Array.isArray(requirements), "Security must be an array");
    for (const requirement of requirements) {
      assert.ok(requirement && typeof requirement === "object" && !Array.isArray(requirement));
      for (const [scheme, scopes] of Object.entries(requirement)) {
        assert.ok(Object.hasOwn(schemes, scheme), `Unknown security scheme ${scheme}`);
        assert.ok(Array.isArray(scopes), "Security scopes must be an array");
      }
    }
  };
  checkSecurity(api.security);
  const tags = new Set(api.tags.map((tag: { name: string }) => tag.name));
  const operations: string[] = [];
  for (const path of Object.values(api.paths)) {
    for (const [method, operation] of Object.entries(path as object)) {
      if (!["get", "post", "put", "patch", "delete", "options", "head", "trace"].includes(method)) continue;
      assert.equal(typeof operation.operationId, "string");
      operations.push(operation.operationId);
      assert.ok(Array.isArray(operation.tags));
      assert.ok(operation.tags.every((tag: string) => tags.has(tag)), "Unknown operation tag");
      checkSecurity(Object.hasOwn(operation, "security") ? operation.security : api.security);
    }
  }
  assert.ok(operations.length > 0);
  assert.equal(new Set(operations).size, operations.length, "Duplicate operation IDs");

  const walk = (value: unknown): void => {
    if (!value || typeof value !== "object") return;
    if (Object.hasOwn(value, "$ref")) {
      const ref = (value as { $ref: string }).$ref;
      assert.ok(typeof ref === "string" && ref.startsWith("#/"), `Invalid internal reference ${ref}`);
      let target: unknown = api;
      for (const part of ref.slice(2).split("/")) {
        const key = part.replaceAll("~1", "/").replaceAll("~0", "~");
        assert.ok(target && typeof target === "object" && Object.hasOwn(target, key), `Unresolved reference ${ref}`);
        target = (target as Record<string, unknown>)[key];
      }
    }
    for (const child of Object.values(value)) walk(child);
  };
  walk(api);
});
