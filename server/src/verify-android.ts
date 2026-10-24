import { readFileSync } from "node:fs";
import { validatePacket } from "./protocol.js";

const fixture = JSON.parse(readFileSync(new URL("../../contracts/fixtures/cp02-signatures.json", import.meta.url), "utf8"));
const packet = JSON.parse(readFileSync(new URL("../../app/build/reports/cp02/android-signature.json", import.meta.url), "utf8"));
validatePacket(packet, { nowMs: fixture.now_ms });
console.log("PASS: server verifies the Android/JCA host-generated signature over shared protobuf bytes");
