import { createHash, createPrivateKey, createPublicKey, sign, timingSafeEqual, verify, type KeyObject } from "node:crypto";

export const SIGNING_DOMAIN = Buffer.from("GHOST-WIRE-V1\0", "utf8");
export const nodeId = (spki: Uint8Array): string => createHash("sha256").update(spki).digest("hex");
export const tokenHash = (token: string): string => createHash("sha256").update(token).digest("hex");

export function publicKey(spki: Buffer): KeyObject {
  const key = createPublicKey({ key: spki, type: "spki", format: "der" });
  if (key.asymmetricKeyType !== "ec" || key.asymmetricKeyDetails?.namedCurve !== "prime256v1" ||
      !spki.equals(key.export({ type: "spki", format: "der" }))) throw new Error("INVALID_KEY");
  return key;
}

export class SigningIdentity {
  readonly publicKey: Buffer;
  readonly nodeId: string;
  constructor(private readonly key: KeyObject) {
    this.publicKey = Buffer.from(createPublicKey(key).export({ type: "spki", format: "der" }));
    publicKey(this.publicKey);
    if (key.type !== "private") throw new Error("INVALID_KEY");
    this.nodeId = nodeId(this.publicKey);
  }
  static fromPem(pem: string): SigningIdentity { return new SigningIdentity(createPrivateKey(pem)); }
  sign(envelope: Uint8Array): Buffer {
    return sign("sha256", Buffer.concat([SIGNING_DOMAIN, envelope]), { key: this.key, dsaEncoding: "der" });
  }
}

export function verifySignature(spki: Buffer, envelope: Uint8Array, signature: Buffer): boolean {
  return verify("sha256", Buffer.concat([SIGNING_DOMAIN, envelope]), publicKey(spki), signature);
}

export type Role = "gateway" | "viewer" | "operator";
export interface Principal { id: string; role: Role; token_sha256: string; gateway_node_id?: string }

export function parsePrincipals(raw: string | undefined): Principal[] {
  if (!raw) return [];
  try {
    const entries: unknown = JSON.parse(raw);
    if (!Array.isArray(entries) || entries.length > 100) throw new Error();
    const ids = new Set<string>();
    const hashes = new Set<string>();
    for (const entry of entries) {
      if (!entry || typeof entry !== "object") throw new Error();
      const p = entry as Principal;
      if (typeof p.id !== "string" || !/^[a-zA-Z0-9_-]{1,80}$/.test(p.id) ||
          !["gateway", "viewer", "operator"].includes(p.role) ||
          typeof p.token_sha256 !== "string" || !/^[a-f0-9]{64}$/.test(p.token_sha256) ||
          (p.role === "gateway" && !/^[a-f0-9]{64}$/.test(p.gateway_node_id ?? "")) ||
          (p.role !== "gateway" && p.gateway_node_id !== undefined) ||
          ids.has(p.id) || hashes.has(p.token_sha256)) throw new Error();
      ids.add(p.id); hashes.add(p.token_sha256);
    }
    return entries as Principal[];
  } catch { throw new Error("Invalid GHOST_AUTH_PRINCIPALS configuration"); }
}

export function authenticate(header: string | undefined, principals: readonly Principal[]): Principal | undefined {
  if (!header?.startsWith("Bearer ")) return undefined;
  const token = header.slice(7);
  if (!/^[A-Za-z0-9_-]{32,256}$/.test(token)) return undefined;
  const digest = Buffer.from(tokenHash(token), "hex");
  return principals.find(p => timingSafeEqual(digest, Buffer.from(p.token_sha256, "hex")));
}
