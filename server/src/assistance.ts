import { randomUUID } from "node:crypto";
import type { Database, SqlConnection } from "./database.js";
import { decode, signCloudPacket, type VerifiedPacket, type WirePacket } from "./protocol.js";
import { SigningIdentity } from "./security.js";

export class AssistanceConflict extends Error { constructor(readonly code: string) { super(code); } }
export async function initializeAssistance(c: SqlConnection) {
  await c.query(`CREATE TABLE IF NOT EXISTS assistance_cases (
    proposal_id UUID PRIMARY KEY, request_id UUID NOT NULL, resource_id TEXT NOT NULL,
    owner_node_id TEXT NOT NULL, requester_node_id TEXT NOT NULL, resource_kind INTEGER NOT NULL,
    resource_event_id UUID NOT NULL, request_event_id UUID NOT NULL, version INTEGER NOT NULL,
    state INTEGER NOT NULL CHECK(state BETWEEN 1 AND 7), quantity INTEGER, unit TEXT NOT NULL,
    deadline_ms BIGINT NOT NULL, data JSONB NOT NULL, operator_id TEXT NOT NULL,
    updated_at_ms BIGINT NOT NULL)`);
  // Upgrade existing deployments without changing stored arrangements.
  await c.query("ALTER TABLE assistance_cases DROP CONSTRAINT IF EXISTS assistance_cases_state_check");
  await c.query("ALTER TABLE assistance_cases ADD CONSTRAINT assistance_cases_state_check CHECK(state BETWEEN 1 AND 7)");
  await c.query("CREATE INDEX IF NOT EXISTS assistance_resource ON assistance_cases(resource_id, state)");
  await c.query(`CREATE TABLE IF NOT EXISTS assistance_events (
    revision BIGSERIAL PRIMARY KEY, packet_id UUID UNIQUE NOT NULL, proposal_id UUID NOT NULL,
    destination TEXT NOT NULL, expires_at_ms BIGINT NOT NULL, packet JSONB NOT NULL)`);
  await c.query("CREATE INDEX IF NOT EXISTS assistance_event_expiry ON assistance_events(expires_at_ms)");
  await c.query(`CREATE TABLE IF NOT EXISTS assistance_decisions (
    event_id UUID PRIMARY KEY, packet_id UUID UNIQUE NOT NULL, envelope BYTEA NOT NULL,
    cloud_revision BIGINT NOT NULL, cloud_ack JSONB NOT NULL)`);
  await c.query(`CREATE TABLE IF NOT EXISTS assistance_downloads (principal_id TEXT PRIMARY KEY, cursor BIGINT NOT NULL)`);
}
const lock = (c: SqlConnection) => c.query("SELECT pg_advisory_xact_lock(715422706)");
const number = (v: any) => Number(v);
async function emit(c: SqlConnection, signer: SigningIdentity, row: any, now: number, reason = "") {
  const payload = { ...row.data, owner_node_id:Buffer.from(row.owner_node_id,"hex"), requester_node_id:Buffer.from(row.requester_node_id,"hex"), proposal_id:row.proposal_id, version:number(row.version), state:number(row.state), reason };
  for (const destination of new Set<string>([row.owner_node_id, row.requester_node_id])) {
    const expiry = row.state === 1 ? number(row.deadline_ms) : now + 86400000;
    const packet = signCloudPacket(signer,16,payload,{packetId:randomUUID(),eventId:randomUUID(),createdAtMs:now,
      expiresAtMs:expiry,destinationNodeId:destination});
    const id = decode("ImmutableEnvelopeV1",Buffer.from(packet.immutable_envelope_bytes,"base64url")).packet_id;
    await c.query(`INSERT INTO assistance_events(packet_id,proposal_id,destination,expires_at_ms,packet)
      VALUES($1,$2,$3,$4,$5::jsonb)`,[id,row.proposal_id,destination,expiry,JSON.stringify(packet)]);
  }
}
async function transition(c: SqlConnection, signer: SigningIdentity, row: any, state: number, now: number, reason: string) {
  const result = await c.query(`UPDATE assistance_cases SET state=$2,version=version+1,updated_at_ms=$3
    WHERE proposal_id=$1 RETURNING *`,[row.proposal_id,state,now]);
  await emit(c,signer,result.rows[0],now,reason);
}
/** Runs under the same lock/transaction as report writes and allocation decisions. */
export async function reconcileAssistance(c: SqlConnection, signer: SigningIdentity, now: number) {
  await lock(c);
  const {rows} = await c.query(`SELECT a.*,s.state AS sos_state,s.expires_at_ms AS sos_expiry,
    r.event_id AS latest_resource,r.expires_at_ms AS resource_expiry
    FROM assistance_cases a JOIN sos_reports s ON s.request_id=a.request_id
    LEFT JOIN resource_offer_latest r ON a.resource_kind=2 AND r.entity_id=a.resource_id
    WHERE a.state IN(1,2)`);
  for (const row of rows) {
    if (row.sos_state !== 1) await transition(c,signer,row,row.sos_state===6 && row.state===2 ? 6 : 4,now,
      row.sos_state===6 ? "Requester marked the SOS resolved" : "Requester cancelled the SOS");
    else if (number(row.deadline_ms)<=now || number(row.sos_expiry)<=now)
      await transition(c,signer,row,5,now,"Assistance deadline expired");
    else if (row.state===1) {
      const resource = (await c.query(`SELECT event_id FROM resource_events WHERE entity_id=$1
        ORDER BY created_at_ms DESC,event_id DESC LIMIT 1`,[row.resource_id])).rows[0];
      if (!resource || resource.event_id!==row.resource_event_id)
        await transition(c,signer,row,4,now,"Resource changed; a new invitation is required");
    }
  }
}
export async function checkCommittedEdit(c: SqlConnection, resourceId: string, quantity: number | null, unit: string) {
  const {rows} = await c.query(`SELECT COALESCE(SUM(quantity),0) AS used,
    COUNT(*) FILTER(WHERE unit<>$2) AS other_units FROM assistance_cases WHERE resource_id=$1 AND state IN(2,6,7)`,[resourceId,unit]);
  if (number(rows[0].used)>0 && (quantity===null || quantity<number(rows[0].used) || number(rows[0].other_units)>0))
    throw new AssistanceConflict("SUPPLIES_ALREADY_COMMITTED");
}
export async function applyAssistanceDecision(c: SqlConnection, signer: SigningIdentity, v: VerifiedPacket,
  revision: number, ack: WirePacket, now: number) {
  await lock(c);
  const {envelope:e,payload:p,packet} = v;
  const bytes = Buffer.from(packet.immutable_envelope_bytes,"base64url");
  const duplicate = await c.query("SELECT * FROM assistance_decisions WHERE event_id=$1 OR packet_id=$2",[e.event_id,e.packet_id]);
  if (duplicate.rows.length) {
    if (duplicate.rows.length!==1 || !Buffer.from(duplicate.rows[0].envelope).equals(bytes)) throw new AssistanceConflict("DECISION_ID_CONFLICT");
    return {duplicate:true,row:duplicate.rows[0]};
  }
  await reconcileAssistance(c,signer,now);
  const row = (await c.query("SELECT * FROM assistance_cases WHERE proposal_id=$1 FOR UPDATE",[p.proposal_id])).rows[0];
  if (!row || (p.decision===4 ? row.requester_node_id : row.owner_node_id)!==e.sender_node_id.toString("hex"))
    throw new AssistanceConflict("INVALID_DECISION_OWNER");
  if (number(row.version)!==p.version || (p.decision>=3 ? row.state!==2 : row.state!==1)) throw new AssistanceConflict("INVITATION_NO_LONGER_CURRENT");
  let state = p.decision===1 ? 2 : p.decision===2 ? 3 : p.decision===4 ? 7 : 4;
  let reason = p.decision===1 ? "Owner accepted; server confirmed commitment" : p.decision===2 ? "Owner declined" :
    p.decision===4 ? "Requester confirmed this assistance received" : "Owner cancelled the commitment";
  if (state===2) {
    const current = (await c.query(`SELECT * FROM resource_events WHERE entity_id=$1 ORDER BY created_at_ms DESC,event_id DESC LIMIT 1`,[row.resource_id])).rows[0];
    const data=current?.data;
    const committed=(await c.query(`SELECT COALESCE(SUM(quantity),0) AS used,COUNT(*) FILTER(WHERE state=2) AS count FROM assistance_cases
      WHERE resource_id=$1 AND state IN(2,6,7)`,[row.resource_id])).rows[0];
    const usable=current && current.event_id===row.resource_event_id && number(current.expires_at_ms)>now &&
      (row.resource_kind===1 ? data.availability===1 && number(committed.count)===0 :
        (data.state??1)===1 && data.quantity!==null && data.unit===row.unit &&
        number(data.quantity)-number(committed.used)>=number(row.quantity));
    if (!usable) { state=4; reason="Resource no longer has enough uncommitted availability"; }
  }
  await transition(c,signer,row,state,now,reason);
  const saved=await c.query(`INSERT INTO assistance_decisions(event_id,packet_id,envelope,cloud_revision,cloud_ack)
    VALUES($1,$2,$3,$4,$5::jsonb) RETURNING *`,[e.event_id,e.packet_id,bytes,revision,JSON.stringify(ack)]);
  return {duplicate:false,row:saved.rows[0]};
}
export class AssistanceService {
  constructor(private readonly database: Database,private readonly signer: SigningIdentity,private readonly now=Date.now) {}
  async review(proposalId:string,approve:boolean,operator:string) {
    return this.database.transaction(async c=> {
      await lock(c); await reconcileAssistance(c,this.signer,this.now());
      const existing=(await c.query("SELECT * FROM assistance_cases WHERE proposal_id=$1",[proposalId])).rows[0];
      if(existing) return {proposal_id:proposalId,state:existing.state};
      const p=(await c.query(`SELECT p.*,s.sender_node_id,s.description,s.coarse_area,s.state AS sos_state,
        s.expires_at_ms AS sos_expiry,s.event_id AS sos_event,r.data AS resource_data,r.expires_at_ms AS resource_expiry,
        r.event_id AS latest_event FROM coordinator_proposals p JOIN sos_reports s ON s.request_id=p.request_id
        LEFT JOIN LATERAL (SELECT * FROM resource_events WHERE entity_id=p.resource_id ORDER BY created_at_ms DESC,event_id DESC LIMIT 1) r ON TRUE
        WHERE proposal_id=$1`,[proposalId])).rows[0];
      if(!p) throw new AssistanceConflict("PROPOSAL_NOT_FOUND");
      const data=p.resource_data;
      const deadline=Math.min(number(p.sos_expiry),number(p.resource_expiry),number(data?.available_until_ms??p.resource_expiry),this.now()+3600000);
      if(approve && (p.sos_state!==1 || p.sos_event!==p.request_event_id || p.latest_event!==p.resource_event_id || deadline<=this.now() ||
        (p.resource_kind==="helper" ? data.availability!==1 : (data.state??1)!==1 || data.quantity===null || p.quantity===null)))
        throw new AssistanceConflict("PROPOSAL_STALE_OR_QUANTITY_UNKNOWN");
      const used=(await c.query(`SELECT COALESCE(SUM(quantity),0) AS used,COUNT(*) FILTER(WHERE state=2) AS count FROM assistance_cases
        WHERE resource_id=$1 AND state IN(2,6,7)`,[p.resource_id])).rows[0];
      if(approve && (p.resource_kind==="helper" ? number(used.count)>0 : number(p.quantity)>number(data.quantity)-number(used.used)))
        throw new AssistanceConflict("RESOURCE_ALREADY_COMMITTED");
      const payload={request_id:p.request_id,resource_id:p.resource_id,resource_kind:p.resource_kind==="helper"?1:2,
        owner_node_id:Buffer.from(p.owner_node_id,"hex"),requester_node_id:Buffer.from(p.sender_node_id,"hex"),
        ...(p.quantity===null?{}:{quantity:number(p.quantity)}),unit:data?.unit??"",description:p.description,
        area:p.coarse_area??"",explanation:p.explanation,deadline_ms:Math.max(this.now()+1,deadline),resource_type:data?.resource_type??0};
      // JSON stores binary identities as hex; emit restores Buffers below.
      const persisted={...payload,owner_node_id:p.owner_node_id,requester_node_id:p.sender_node_id};
      const row=(await c.query(`INSERT INTO assistance_cases(proposal_id,request_id,resource_id,owner_node_id,requester_node_id,
        resource_kind,resource_event_id,request_event_id,version,state,quantity,unit,deadline_ms,data,operator_id,updated_at_ms)
        VALUES($1,$2,$3,$4,$5,$6,$7,$8,1,$9,$10,$11,$12,$13::jsonb,$14,$15) RETURNING *`,
        [proposalId,p.request_id,p.resource_id,p.owner_node_id,p.sender_node_id,payload.resource_kind,p.resource_event_id,p.request_event_id,
          approve?1:3,p.quantity,payload.unit,payload.deadline_ms,JSON.stringify(persisted),operator,this.now()])).rows[0];
      if(approve) await emit(c,this.signer,row,this.now(),"Operator approved; waiting for owner");
      return {proposal_id:proposalId,state:row.state};
    });
  }
  async sweep() { await this.database.transaction(async c=>{
    await reconcileAssistance(c,this.signer,this.now());
    await c.query("DELETE FROM assistance_events WHERE expires_at_ms<=$1",[this.now()]);
  }); }
  async list() { await this.sweep(); const {rows}=await this.database.query(`SELECT proposal_id,request_id,resource_id,resource_kind,state,
    quantity,unit,version,deadline_ms,updated_at_ms FROM assistance_cases ORDER BY updated_at_ms DESC,proposal_id LIMIT 100`);
    return {items:rows.map(r=>({...r,deadline_ms:number(r.deadline_ms),updated_at_ms:number(r.updated_at_ms)}))}; }
  async acknowledge(principal:string,cursor:string,ids:string[]) {
    if(new Set(ids).size!==ids.length)throw new AssistanceConflict("INVALID_DOWNLOAD_ACK");
    const {rows}=await this.database.query(`SELECT COUNT(*) AS count,MAX(revision) AS last FROM assistance_events
      WHERE packet_id=ANY($1::uuid[]) AND revision<=$2`,[ids,cursor]);
    if(Number(rows[0].count)!==ids.length || ids.length>0 && String(rows[0].last)!==cursor)
      throw new AssistanceConflict("INVALID_DOWNLOAD_ACK");
    await this.database.query(`INSERT INTO assistance_downloads(principal_id,cursor) VALUES($1,$2)
      ON CONFLICT(principal_id) DO UPDATE SET cursor=GREATEST(assistance_downloads.cursor,EXCLUDED.cursor)`,[principal,cursor]);
  }
  async download(cursor:number,limit:number) {
    await this.sweep();
    const {rows}=await this.database.query(`SELECT * FROM assistance_events WHERE revision>$1 AND expires_at_ms>$2
      ORDER BY revision LIMIT $3`,[cursor,this.now(),limit]);
    return {cursor:String(cursor),next_cursor:String(rows.length?rows.at(-1)!.revision:cursor),packets:rows.map(r=>r.packet)};
  }
}
