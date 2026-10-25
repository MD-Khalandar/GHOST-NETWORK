import type { SqlConnection } from "./database.js";
import { AssistanceConflict,reconcileAssistance } from "./assistance.js";
import type { SigningIdentity } from "./security.js";
import { decode,type VerifiedPacket,type WirePacket } from "./protocol.js";

export async function saveChat(c:SqlConnection,signer:SigningIdentity,v:VerifiedPacket,revision:number,ack:WirePacket,now:number) {
  await c.query("SELECT pg_advisory_xact_lock(715422706)");
  const e=v.envelope,p=v.payload,bytes=Buffer.from(v.packet.immutable_envelope_bytes,"base64url");
  const prior=(await c.query("SELECT * FROM assistance_decisions WHERE event_id=$1 OR packet_id=$2",[e.event_id,e.packet_id])).rows;
  if(prior.length) {
    if(prior.length!==1 || !Buffer.from(prior[0].envelope).equals(bytes))throw new AssistanceConflict("DECISION_ID_CONFLICT");
    return {duplicate:true,row:prior[0]};
  }
  await reconcileAssistance(c,signer,now);
  const row=(await c.query("SELECT * FROM assistance_cases WHERE proposal_id=$1",[p.proposal_id])).rows[0];
  const sender=e.sender_node_id.toString("hex");
  if(!row || ![row.owner_node_id,row.requester_node_id].includes(sender))throw new AssistanceConflict("INVALID_CHAT_PARTICIPANT");
  if(p.type!==3) {
    const proof=decode("AssistanceStatusV1",decode("ImmutableEnvelopeV1",p.status_envelope).payload_bytes);
    if(row.state!==2 || Number(row.version)!==proof.version || Number(row.deadline_ms)<=now)throw new AssistanceConflict("CHAT_CLOSED");
  }
  const budget=(await c.query(`SELECT COUNT(*) AS total FROM assistance_events WHERE proposal_id=$1 AND kind=18 AND expires_at_ms>$2`,[p.proposal_id,now])).rows[0];
  if(Number(budget.total)>=400)throw new AssistanceConflict("CHAT_LIMIT_REACHED");
  await c.query(`INSERT INTO assistance_events(packet_id,proposal_id,destination,expires_at_ms,packet,kind)
    VALUES($1,$2,$3,$4,$5::jsonb,18)`,[e.packet_id,p.proposal_id,e.destination.node_id.toString("hex"),e.expires_at_ms,JSON.stringify(v.packet)]);
  const saved=await c.query(`INSERT INTO assistance_decisions(event_id,packet_id,envelope,cloud_revision,cloud_ack)
    VALUES($1,$2,$3,$4,$5::jsonb) RETURNING *`,[e.event_id,e.packet_id,bytes,revision,JSON.stringify(ack)]);
  return {duplicate:false,row:saved.rows[0]};
}
