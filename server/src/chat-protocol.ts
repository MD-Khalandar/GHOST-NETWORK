import { createPublicKey } from "node:crypto";
import { decode,validatePacket,PacketError,type ValidationOptions } from "./protocol.js";

const check=(v:unknown)=>{if(!v)throw new PacketError("INVALID_PAYLOAD");};
const hex=(v:Buffer|undefined)=>v?.toString("hex")??"";
const empty=(p:any,fields:string[])=>fields.every(k=>!p[k]?.length);
export function validateChat(e:any,p:any,options:ValidationOptions) {
  check([1,2,3].includes(p.type) && p.status_envelope?.length>0 && p.status_envelope.length<=8192 && p.status_signature?.length<=72);
  const s=decode("ImmutableEnvelopeV1",p.status_envelope);
  check(s.kind===16 && s.created_at_ms<=e.created_at_ms);
  const verified=validatePacket({immutable_envelope_bytes:p.status_envelope.toString("base64url"),signature:p.status_signature.toString("base64url"),relay:{remaining_hops:4,hop_count:0}},
    {nowMs:Math.min(options.nowMs,s.expires_at_ms-1),trustedCloudNodeId:options.trustedCloudNodeId});
  const status=verified.payload,sender=hex(e.sender_node_id);
  check(status.state===2 && p.proposal_id===status.proposal_id && e.destination.kind===2);
  const other=sender===hex(status.owner_node_id)?hex(status.requester_node_id):sender===hex(status.requester_node_id)?hex(status.owner_node_id):null;
  if(!other || other!==hex(e.destination.node_id))throw new PacketError("INVALID_ACTOR");
  check(hex(s.destination.node_id)===(p.type===3?other:sender));
  if(p.type!==3)check(e.created_at_ms<status.deadline_ms && e.expires_at_ms<=Math.min(status.deadline_ms,s.expires_at_ms));
  if(p.type===1) {
    check(p.rsa_public_key?.length===294);
    const key=createPublicKey({key:p.rsa_public_key,format:"der",type:"spki"});
    check(key.asymmetricKeyType==="rsa" && key.asymmetricKeyDetails?.modulusLength===2048 && key.asymmetricKeyDetails.publicExponent===65537n &&
      key.export({format:"der",type:"spki"}).equals(p.rsa_public_key));
    check(empty(p,["message_id","ciphertext","nonce","wrapped_key","sender_wrapped_key","recipient_key_envelope","recipient_key_signature"]));
  }else if(p.type===2) {
    check(p.message_id===e.event_id && p.nonce?.length===12 && p.ciphertext?.length>=17 && p.ciphertext.length<=4016 && p.wrapped_key?.length===256 && p.sender_wrapped_key?.length===256 &&
      !p.rsa_public_key?.length && p.recipient_key_envelope?.length>0 && p.recipient_key_envelope.length<=12288 && p.recipient_key_signature?.length<=72);
    const k=decode("ImmutableEnvelopeV1",p.recipient_key_envelope);
    check(k.kind===18 && decode("PrivateChatV1",k.payload_bytes).type===1);
    const peer=validatePacket({immutable_envelope_bytes:p.recipient_key_envelope.toString("base64url"),signature:p.recipient_key_signature.toString("base64url"),relay:{remaining_hops:4,hop_count:0}},
      {nowMs:e.created_at_ms,trustedCloudNodeId:options.trustedCloudNodeId});
    check(peer.payload.proposal_id===p.proposal_id && hex(k.sender_node_id)===other && hex(k.destination.node_id)===sender && e.expires_at_ms<=k.expires_at_ms);
  }else {
    check(typeof p.message_id==="string" && /^[a-f0-9]{8}-[a-f0-9]{4}-[1-5][a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/.test(p.message_id) &&
      empty(p,["ciphertext","nonce","wrapped_key","sender_wrapped_key","rsa_public_key","recipient_key_envelope","recipient_key_signature"]));
  }
}
