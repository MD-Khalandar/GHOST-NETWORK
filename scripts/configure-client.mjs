import { createPublicKey } from "node:crypto";
import { mkdirSync, writeFileSync } from "node:fs";
import { networkInterfaces } from "node:os";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

// Run once per deployment/network change. Only public settings are written for the Android build.
process.chdir(fileURLToPath(new URL("../", import.meta.url)));
try {
  process.loadEnvFile("server/.env");
  const interfaces = networkInterfaces();
  const addresses = Object.entries(interfaces).flatMap(([name, values]) =>
    (values ?? []).filter(value => value.family === "IPv4" && !value.internal).map(value => ({ name, address: value.address })));
  const local = addresses.find(value => value.name === "en0") ?? addresses[0];
  const supplied = process.argv[2] || process.env.GHOST_SERVER_URL;
  if (!supplied && !local) throw new Error("Pass the reachable server URL as the first argument.");
  const url = new URL(supplied || `http://${local.address}:${process.env.GHOST_PORT || "3000"}`);
  if (!["http:", "https:"].includes(url.protocol) || url.username || url.password || url.search || url.hash)
    throw new Error("Use an HTTP(S) server URL without credentials, query or fragment.");
  const pem = process.env.GHOST_CLOUD_SIGNING_KEY_PEM?.replaceAll("\\n", "\n");
  if (!pem) throw new Error("Configure the existing server signing key in server/.env first.");
  const key = createPublicKey(pem);
  if (key.asymmetricKeyType !== "ec" || key.asymmetricKeyDetails?.namedCurve !== "prime256v1")
    throw new Error("The existing server key must use P-256.");
  const publicKey = key.export({ type: "spki", format: "der" }).toString("base64url");
  const folder = resolve(".local/phone-server-test");
  mkdirSync(folder, { recursive: true, mode: 0o700 });
  writeFileSync(resolve(folder, "client.properties"), `server_url=${url.href.replace(/\/$/, "")}\ncloud_public_key=${publicKey}\n`, { mode: 0o600 });
  console.log(`Android connection configured for ${url.href.replace(/\/$/, "")}. No private key or token was exported.`);
} catch (error) {
  console.error(error instanceof Error ? error.message : "Could not prepare public client settings.");
  process.exitCode = 1;
}
