import { createInterface } from "node:readline/promises";
import { Writable } from "node:stream";
import { loadConfig } from "./config.js";
import { postgresDatabase } from "./database.js";
import { disableDashboardAccount, initializeDashboardAuth, normalizeUsername, saveDashboardAccount } from "./dashboard-auth.js";

async function main() {
  const args = process.argv.slice(2);
  let username: string | undefined, role: "viewer" | "operator" = "operator", disable = false;
  for (let i = 0; i < args.length; i++) {
    if (args[i] === "--username" && !username) username = normalizeUsername(args[++i] ?? "");
    else if (args[i] === "--role" && ["viewer", "operator"].includes(args[i + 1])) role = args[++i] as typeof role;
    else if (args[i] === "--disable") disable = true;
    else throw new Error("Arguments");
  }
  if (!username) throw new Error("Arguments");
  const config = loadConfig();
  if (!config.databaseUrl) throw new Error("Storage");
  let password = "";
  if (!disable) {
    if (!process.stdin.isTTY || !process.stdout.isTTY) throw new Error("Terminal");
    let muted = false;
    const output = new Writable({ write(chunk, _encoding, done) { if (!muted) process.stdout.write(chunk); done(); } });
    const reader = createInterface({ input: process.stdin, output, terminal: true });
    const abort = new AbortController();
    reader.once("SIGINT", () => abort.abort());
    const secret = async (prompt: string) => {
      const answer = reader.question(prompt, { signal: abort.signal });
      muted = true;
      try { return await answer; }
      finally { muted = false; process.stdout.write("\n"); }
    };
    try {
      password = await secret("Password (15-256 characters; hidden): ");
      const confirmation = await secret("Confirm password: ");
      if (password !== confirmation || password.length < 15 || password.length > 256) throw new Error("Password");
    } finally { reader.close(); output.end(); }
  }
  const database = postgresDatabase(config.databaseUrl);
  try {
    await database.transaction(async c => {
      await c.query("SELECT pg_advisory_xact_lock(715422701)");
      await initializeDashboardAuth(c);
    });
    if (disable) await disableDashboardAccount(database, username);
    else await saveDashboardAccount(database, username, role, password);
    console.log(disable ? "Dashboard account disabled; existing sessions revoked." : "Dashboard account saved; previous sessions revoked. Sign in with your username and password.");
  } finally { password = ""; await database.close(); }
}

main().catch(() => {
  console.error("Account setup failed. Run in an interactive terminal with server/.env configured: npm --prefix server run account -- --username admin --role operator (or --disable). Passwords must match and contain 15-256 characters. Check database connectivity; no credentials were printed.");
  process.exitCode = 1;
});
