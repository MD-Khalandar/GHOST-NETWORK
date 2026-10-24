import assert from "node:assert/strict";
import test from "node:test";
import { renderToStaticMarkup } from "react-dom/server";
import { SignInForm } from "./App";
import { ApiError, get, login, logout, post } from "./api";

test("sign-in uses password-manager fields and explains remembered sessions without exposing token entry", () => {
  const html = renderToStaticMarkup(<SignInForm username="" password="" busy={false} error={null}
    onUsername={() => {}} onPassword={() => {}} onSubmit={() => {}}/>);
  assert.match(html, /autoComplete="username"/); assert.match(html, /autoComplete="current-password"/);
  assert.match(html, /type="password"/); assert.match(html, /seven days/);
  assert.doesNotMatch(html, /Access token|localStorage|sessionStorage/);
  const error = renderToStaticMarkup(<SignInForm username="admin" password="" busy error="<script>error</script>"
    onUsername={() => {}} onPassword={() => {}} onSubmit={() => {}}/>);
  assert.match(error, /role="alert"/); assert.doesNotMatch(error, /<script>/); assert.match(error, /disabled/);
});

test("dashboard requests use cookies, CSRF for mutations and server-side logout rather than bearer storage", async () => {
  const saved = globalThis.fetch;
  const calls: { path: string; options: RequestInit }[] = [];
  const session = { id: "desk-test", username: "admin", role: "operator", csrf_token: "c".repeat(43), expires_at_ms: 1234567 };
  globalThis.fetch = async (path, options) => {
    calls.push({ path: String(path), options: options! });
    return String(path).endsWith("/logout") ? new Response(null, { status: 204 }) : Response.json(session);
  };
  try {
    const signal = new AbortController().signal;
    assert.deepEqual(await login("admin", "a password used only in this test", signal), session);
    assert.deepEqual(await get("auth/session", signal), session); // Reload restores the server cookie, without a token argument.
    await post("dashboard/proposals/id/approve", session.csrf_token, signal);
    await logout(session.csrf_token, signal);
    await post("dashboard/coordination-settings",session.csrf_token,signal,{manual_review:true});
    assert.ok(calls.every(call => call.options.credentials === "same-origin" && call.options.cache === "no-store"));
    assert.ok(calls.every(call => !new Headers(call.options.headers).has("Authorization")));
    assert.deepEqual(JSON.parse(calls[0].options.body as string), { username: "admin", password: "a password used only in this test" });
    assert.equal(new Headers(calls[0].options.headers).get("X-Ghost-Desk"), "1");
    assert.equal(new Headers(calls[2].options.headers).get("X-CSRF-Token"), session.csrf_token);
    assert.equal(calls[3].options.method, "POST");
    assert.equal(new Headers(calls[3].options.headers).get("X-CSRF-Token"), session.csrf_token);
    assert.deepEqual(JSON.parse(calls[4].options.body as string),{manual_review:true});
    assert.equal(new Headers(calls[4].options.headers).get("Content-Type"),"application/json");
    globalThis.fetch = async () => Response.json({ code: "INVALID_CREDENTIALS" }, { status: 401 });
    await assert.rejects(login("admin", "wrong", signal), error => error instanceof ApiError && /Username or password/.test(error.message));
  } finally { globalThis.fetch = saved; }
});
