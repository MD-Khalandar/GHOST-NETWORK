import { createHash } from "node:crypto";
import type { SqlConnection } from "../database.js";
import type { FollowUp, MatchContext, Specialist } from "./types.js";
import { deliveryRoundSql } from "./delivery-round.js";

export async function initializeFollowUp(c:SqlConnection) {
  await c.query(`CREATE TABLE IF NOT EXISTS coordinator_tasks (
    request_id UUID NOT NULL REFERENCES sos_reports(request_id), specialist TEXT NOT NULL CHECK(specialist IN('helper','supply')),
    input_hash TEXT, status TEXT NOT NULL, last_run_id UUID, updated_at_ms BIGINT NOT NULL,
    PRIMARY KEY(request_id,specialist))`);
  await c.query("ALTER TABLE coordinator_runs ADD COLUMN IF NOT EXISTS specialist TEXT");
}

export async function readFollowUp(c:SqlConnection,requestId:string,specialist:Specialist,now:number):Promise<FollowUp> {
  const kind=specialist==="helper"?1:2;
  const {rows}=await c.query(`SELECT a.proposal_id,a.resource_id,a.state,a.version,a.quantity,a.unit,r.data->>'resource_type' AS resource_type FROM assistance_cases a
    LEFT JOIN resource_events r ON r.event_id=a.resource_event_id
    WHERE a.request_id=$1 AND a.resource_kind=$2 ORDER BY CASE WHEN a.state IN(1,2) THEN 0 ELSE 1 END,a.updated_at_ms DESC,a.proposal_id LIMIT 50`,[requestId,kind]);
  const pending=await c.query(`SELECT COUNT(*) AS count FROM coordinator_proposals p
    JOIN sos_reports s ON s.event_id=p.request_event_id
    JOIN coordinator_resource_available r ON r.entity_id=p.resource_id AND r.event_id=p.resource_event_id
    WHERE p.request_id=$1 AND p.resource_kind=$2 AND s.state=1 AND s.expires_at_ms>$3 AND r.expires_at_ms>$3
      AND p.delivery_round=${deliveryRoundSql("p.request_id","p.resource_id")}
      AND NOT EXISTS(SELECT 1 FROM assistance_cases a WHERE a.proposal_id=p.proposal_id)
      AND (p.resource_kind='helper' AND r.data->>'availability'='1' OR p.resource_kind='offer'
        AND COALESCE(r.data->>'state','1')='1' AND COALESCE((r.data->>'available_until_ms')::bigint,r.expires_at_ms)>$3
        AND p.quantity IS NOT NULL AND (r.data->>'quantity')::bigint>=p.quantity)`,
    [requestId,specialist==="helper"?"helper":"offer",now]);
  return {awaiting_review:Number(pending.rows[0].count),arrangements:rows.map(r=>({
    proposal_id:r.proposal_id,resource_id:r.resource_id,state:Number(r.state),version:Number(r.version),
    quantity:r.quantity===null?null:Number(r.quantity),unit:r.unit,resource_type:r.resource_type===null?null:Number(r.resource_type)}))};
}

export function fingerprint(context:MatchContext) {
  return createHash("sha256").update(JSON.stringify(context)).digest("hex");
}
export function waitingStatus(context:MatchContext):string|null {
  if(context.follow_up?.arrangements.some(a=>a.state===2))return "committed";
  if(context.follow_up?.arrangements.some(a=>a.state===1))return "awaiting_owner";
  if(context.follow_up?.awaiting_review)return "awaiting_review";
  return null;
}
export async function recordTask(c:SqlConnection,context:MatchContext,status:string,now:number,runId:string|null=null) {
  if(!context.specialist)return;
  const hash=["searching","error","budget_paused"].includes(status)?null:fingerprint(context);
  await c.query(`INSERT INTO coordinator_tasks(request_id,specialist,input_hash,status,last_run_id,updated_at_ms)
    VALUES($1,$2,$3,$4,$5,$6) ON CONFLICT(request_id,specialist) DO UPDATE SET
    input_hash=EXCLUDED.input_hash,status=EXCLUDED.status,last_run_id=COALESCE(EXCLUDED.last_run_id,coordinator_tasks.last_run_id),
    updated_at_ms=EXCLUDED.updated_at_ms WHERE coordinator_tasks.status NOT IN('closed','expired') AND
      (coordinator_tasks.input_hash IS DISTINCT FROM EXCLUDED.input_hash
      OR coordinator_tasks.status IS DISTINCT FROM EXCLUDED.status OR
      (EXCLUDED.last_run_id IS NOT NULL AND coordinator_tasks.last_run_id IS DISTINCT FROM EXCLUDED.last_run_id))`,
    [context.request.request_id,context.specialist,hash,status,runId,now]);
}

export const taskLabels:Record<string,string>={
  searching:"Looking for the next suitable option",proposed:"Suggestion ready; waiting for invitation or review",
  awaiting_review:"Waiting for operator review",awaiting_owner:"Invitation sent; waiting for the owner",
  committed:"Help committed; waiting for requester receipt confirmation",
  no_candidates:"No untried available resources; waiting for new availability",
  no_match:"No suitable next option found; operator attention may be needed",
  error:"Specialist could not finish; check the run status",budget_paused:"Waiting for the shared AI call budget",
  closed:"SOS closed; follow-up stopped",expired:"SOS expired; follow-up stopped",
  receipt_recorded:"Receipt recorded; the SOS remains open for any further needs",
  needs_replan:"Earlier arrangement ended; checking for a suitable alternative",
};
