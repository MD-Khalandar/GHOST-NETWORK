import { createHash } from "node:crypto";
import type { Database } from "./database.js";

export interface UploadLimits { perDevice: number; perIp: number; global: number }
export const DEFAULT_UPLOAD_LIMITS: Readonly<UploadLimits> = { perDevice: 600, perIp: 6000, global: 12000 };
export class UploadThrottled extends Error {
  constructor(readonly retryAfterSeconds: number) { super("UPLOAD_BUSY"); }
}

export async function initializeUploadLimits(database: Database): Promise<void> {
  await database.transaction(async connection => {
    await connection.query("SELECT pg_advisory_xact_lock(715422701)");
    await connection.query(`CREATE TABLE IF NOT EXISTS upload_windows (
      scope TEXT NOT NULL, window_start_ms BIGINT NOT NULL, packets INTEGER NOT NULL CHECK (packets > 0),
      PRIMARY KEY (scope, window_start_ms)
    )`);
  });
}

/** Packet-weighted budgets persist across restarts, renewal and server instances. */
export class UploadLimiter {
  private readonly limits: UploadLimits;
  constructor(private readonly database: Database, limits: Partial<UploadLimits> = {}, private readonly now = Date.now) {
    this.limits = { ...DEFAULT_UPLOAD_LIMITS, ...limits };
    for (const limit of Object.values(this.limits)) if (!Number.isSafeInteger(limit) || limit < 1 || limit > 1000000) throw new Error("Invalid upload limits");
  }
  async admit(deviceId: string, ip: string, packets: number): Promise<void> {
    if (!Number.isInteger(packets) || packets < 1 || packets > 50) throw new Error("Invalid upload packet count");
    const now = this.now(), window = Math.floor(now / 60000) * 60000;
    const ipHash = createHash("sha256").update(ip).digest("hex");
    await this.database.transaction(async connection => {
      // All three scopes are admitted atomically: an IP/global throttle cannot
      // consume a device's remaining budget or persist any partial report.
      await connection.query("SELECT pg_advisory_xact_lock(715422705)");
      await connection.query("DELETE FROM upload_windows WHERE window_start_ms < $1", [window]);
      for (const [scope, limit] of [[`device:${deviceId}`, this.limits.perDevice], [`ip:${ipHash}`, this.limits.perIp], ["global", this.limits.global]] as const) {
        const result = await connection.query(`INSERT INTO upload_windows(scope, window_start_ms, packets)
          SELECT $1,$2::bigint,$3::integer WHERE $3::integer <= $4::integer ON CONFLICT (scope, window_start_ms)
          DO UPDATE SET packets = upload_windows.packets + EXCLUDED.packets
          WHERE upload_windows.packets + EXCLUDED.packets <= $4::integer RETURNING packets`, [scope, window, packets, limit]);
        if (!result.rows.length) throw new UploadThrottled(Math.max(1, Math.ceil((window + 60000 - now) / 1000)));
      }
    });
  }
}
