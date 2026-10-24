import assert from "node:assert/strict";
import test from "node:test";
import { PGlite } from "@electric-sql/pglite";
import { generateKeyPairSync,randomUUID } from "node:crypto";
import { buildApp } from "./app.js";
import { SigningIdentity,tokenHash } from "./security.js";
import { createPacket,decode,validatePacket,type WirePacket } from "./protocol.js";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import type { Database } from "./database.js";
import { initializeAssistance } from "./assistance.js";

const key=()=>SigningIdentity.fromPem(generateKeyPairSync("ec",{namedCurve:"prime256v1"}).privateKey.export({format:"pem",type:"pkcs8"}).toString());
async function fixture(path?: string) {
  const pg=new PGlite(path);let clock=1792860000000;
  const cloudPem=generateKeyPairSync("ec",{namedCurve:"prime256v1"}).privateKey.export({format:"pem",type:"pkcs8"}).toString();
  const cloud=SigningIdentity.fromPem(cloudPem),requester=key(),supplier=key(),other=key();
  const tokens={gateway:"g".repeat(43),viewer:"v".repeat(43),operator:"o".repeat(43)};
  let failStatusWrite=false;
  const database:Database={query:(q,v)=>pg.query(q,v),transaction:work=>pg.transaction(c=>work({query:(q,v)=>{
    if(failStatusWrite && q.includes("INSERT INTO assistance_events"))return Promise.reject(new Error("simulated write failure"));
    return c.query(q,v);
  }})),close:()=>pg.close()};
  const app=buildApp({database,cloudSigningKeyPem:cloudPem,now:()=>clock,
    principals:Object.entries(tokens).map(([role,token])=>({id:role,role:role as "gateway"|"viewer"|"operator",token_sha256:tokenHash(token),...(role==="gateway"?{gateway_node_id:other.nodeId}:{})}))});
  const headers=(role:keyof typeof tokens="gateway")=>({authorization:`Bearer ${tokens[role]}`});
  const packet=(owner:SigningIdentity,kind:number,payload:Record<string,unknown>)=>createPacket(owner,kind,payload,{packetId:randomUUID(),eventId:randomUUID(),createdAtMs:clock,expiresAtMs:clock+7200000});
  const upload=(p:WirePacket,id:string=randomUUID())=>app.inject({method:"POST",url:"/v1/sync/upload",headers:headers(),payload:{idempotency_key:id,packets:[p]}});
  const consent={public_mesh_allowed:true,cloud_processing_allowed:true};
  const requestId=randomUUID(),offerId=randomUUID();
  const original=packet(requester,1,{request_id:requestId,category:3,description:"Need drinking water",coarse_area:"Station",consent});
  const offer=packet(supplier,5,{offer_id:offerId,owner_node_id:Buffer.from(supplier.nodeId,"hex"),resource_type:1,quantity:10,unit:"litres",consent});
  assert.equal((await upload(original)).statusCode,200);assert.equal((await upload(offer)).statusCode,200);
  async function proposal(request: string = requestId,resource: string = offerId,kind="offer",quantity:number|null=7) {
    const run=randomUUID(),id=randomUUID();
    const sos=(await pg.query<any>("SELECT * FROM sos_reports WHERE request_id=$1",[request])).rows[0];
    const r=(await pg.query<any>("SELECT * FROM resource_events WHERE entity_id=$1 ORDER BY created_at_ms DESC,event_id DESC LIMIT 1",[resource])).rows[0];
    await pg.query("INSERT INTO coordinator_runs(run_id,request_id,status,created_at_ms) VALUES($1,$2,'completed',$3)",[run,request,clock]);
    await pg.query(`INSERT INTO coordinator_proposals(proposal_id,run_id,request_id,resource_kind,resource_id,owner_node_id,request_event_id,request_revision,
      resource_event_id,resource_revision,quantity,explanation,created_at_ms) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,'Possible help',$12)`,
      [id,run,request,kind,resource,r.owner_node_id,sos.event_id,sos.cloud_revision,r.event_id,r.cloud_revision,quantity,clock]);return id;
  }
  const review=(id:string,action="approve",role:keyof typeof tokens="operator")=>app.inject({method:"POST",url:`/v1/dashboard/proposals/${id}/${action}`,headers:headers(role)});
  async function status(id:string,state=1,destination=supplier.nodeId) {
    const rows=(await pg.query<any>("SELECT packet FROM assistance_events WHERE proposal_id=$1 AND destination=$2 ORDER BY revision DESC",[id,destination])).rows;
    const found=rows.map(r=>r.packet as WirePacket).find(p=>decode("AssistanceStatusV1",decode("ImmutableEnvelopeV1",Buffer.from(p.immutable_envelope_bytes,"base64url")).payload_bytes).state===state);
    assert.ok(found);validatePacket(found,{nowMs:clock,trustedCloudNodeId:cloud.nodeId});return found;
  }
  const decision=(proof:WirePacket,choice=1,owner=supplier)=>{
    const e=decode("ImmutableEnvelopeV1",Buffer.from(proof.immutable_envelope_bytes,"base64url"));const p=decode("AssistanceStatusV1",e.payload_bytes);
    return createPacket(owner,17,{proposal_id:p.proposal_id,version:p.version,decision:choice,status_envelope:Buffer.from(proof.immutable_envelope_bytes,"base64url"),status_signature:Buffer.from(proof.signature,"base64url")},{packetId:randomUUID(),eventId:randomUUID(),createdAtMs:clock,expiresAtMs:e.expires_at_ms});
  };
  const closure=(state:number)=>createPacket(requester,2,{request_id:requestId,state,original_sos_envelope:Buffer.from(original.immutable_envelope_bytes,"base64url"),original_sos_signature:Buffer.from(original.signature,"base64url")},{packetId:randomUUID(),eventId:randomUUID(),createdAtMs:clock,expiresAtMs:decode("ImmutableEnvelopeV1",Buffer.from(original.immutable_envelope_bytes,"base64url")).expires_at_ms});
  return {pg,app,cloud,cloudPem,tokens,failStatus:(value:boolean)=>{failStatusWrite=value;},supplier,requester,other,headers,packet,upload,proposal,review,status,decision,closure,original,offer,offerId,requestId,consent,
    advance:(ms=1000)=>{clock+=ms},clock:()=>clock};
}

test("operator approval, signed owner acceptance and download confirmation reserve stock once and enforce roles",async()=>{
  const f=await fixture();try{
    const id=await f.proposal();
    assert.equal((await f.review(id,"approve","viewer")).statusCode,403);
    assert.equal((await f.review(id,"approve","gateway")).statusCode,403);
    assert.equal((await f.review(id)).statusCode,200);assert.equal((await f.review(id)).statusCode,200);
    assert.equal((await f.pg.query("SELECT * FROM assistance_events")).rows.length,2);
    const invite=await f.status(id);
    assert.equal((await f.upload(f.decision(invite,1,f.other))).statusCode,400);
    const accepted=f.decision(invite);
    const response=await f.upload(accepted,"accept-once");assert.equal(response.statusCode,200,response.body);
    assert.equal((await f.upload(accepted,"another-gateway-path")).statusCode,200);
    assert.equal((await f.pg.query("SELECT * FROM assistance_decisions")).rows.length,1);
    await f.status(id,2,f.requester.nodeId);
    const balances=(await f.app.inject({url:"/v1/dashboard/offers",headers:f.headers("viewer")})).json();
    assert.equal(balances.items[0].reserved_quantity,7);assert.equal(balances.items[0].available_quantity,3);
    const download=await f.app.inject({url:"/v1/sync/download?cursor=0&limit=50",headers:f.headers()});
    assert.equal(download.statusCode,200);assert.equal(download.json().packets.length,4);
    assert.equal((await f.app.inject({url:"/v1/sync/download",headers:f.headers("viewer")})).statusCode,403);
    const ack=await f.app.inject({method:"POST",url:"/v1/sync/download-ack",headers:f.headers(),payload:{cursor:download.json().next_cursor,packet_ids:download.json().packets.map((p:WirePacket)=>decode("ImmutableEnvelopeV1",Buffer.from(p.immutable_envelope_bytes,"base64url")).packet_id)}});
    assert.equal(ack.statusCode,204,ack.body);
    assert.equal((await f.app.inject({url:`/v1/sync/download?cursor=${download.json().next_cursor}`,headers:f.headers()})).json().packets.length,0);
    f.advance();assert.equal((await f.upload(f.closure(6))).statusCode,200);
    const after=(await f.app.inject({url:"/v1/dashboard/offers",headers:f.headers("viewer")})).json().items[0];
    assert.equal(after.reserved_quantity,0);assert.equal(after.consumed_quantity,7);assert.equal(after.available_quantity,3);
    await f.status(id,6,f.requester.nodeId);
  }finally{await f.app.close();}
});

test("only the requester can confirm individual assistance received; stock is consumed once and the SOS stays open",async()=>{
  const f=await fixture();try{
    const id=await f.proposal();await f.review(id);
    assert.equal((await f.upload(f.decision(await f.status(id,1,f.requester.nodeId),4,f.requester))).statusCode,400);
    await f.upload(f.decision(await f.status(id)));
    const proof=await f.status(id,2,f.requester.nodeId);
    assert.equal((await f.upload(f.decision(proof,4,f.supplier))).statusCode,400);
    assert.equal((await f.upload(f.decision(proof,4,f.other))).statusCode,400);
    assert.equal((await f.upload(f.decision(await f.status(id,2),4))).statusCode,400);
    assert.equal((await f.upload(f.decision(proof,3,f.requester))).statusCode,400);
    const received=f.decision(proof,4,f.requester);
    f.failStatus(true);
    assert.equal((await f.upload(received,"receipt-retry")).statusCode,503);
    assert.equal((await f.pg.query<any>("SELECT state FROM assistance_cases WHERE proposal_id=$1",[id])).rows[0].state,2);
    f.failStatus(false);
    assert.equal((await f.upload(received,"receipt-retry")).statusCode,200);
    const count=Number((await f.pg.query<any>("SELECT COUNT(*) AS count FROM assistance_events")).rows[0].count);
    assert.equal((await f.upload(received)).statusCode,200);
    assert.equal(Number((await f.pg.query<any>("SELECT COUNT(*) AS count FROM assistance_events")).rows[0].count),count);
    await f.status(id,7);await f.status(id,7,f.requester.nodeId);
    assert.equal((await f.pg.query<any>("SELECT state FROM sos_reports WHERE request_id=$1",[f.requestId])).rows[0].state,1);
    const balance=(await f.app.inject({url:"/v1/dashboard/offers",headers:f.headers("viewer")})).json().items[0];
    assert.equal(balance.reserved_quantity,0);assert.equal(balance.consumed_quantity,7);assert.equal(balance.available_quantity,3);
    assert.equal((await f.pg.query<any>("SELECT data FROM coordinator_resource_available WHERE entity_id=$1",[f.offerId])).rows[0].data.quantity,3);
    assert.equal((await f.upload(f.decision(proof,4,f.requester))).statusCode,409);
    assert.equal((await f.upload(f.decision(await f.status(id,2),3))).statusCode,409);
    f.advance();await f.upload(f.closure(5));
    assert.equal((await f.pg.query<any>("SELECT state FROM assistance_cases WHERE proposal_id=$1",[id])).rows[0].state,7);
    assert.equal((await f.app.inject({url:"/v1/dashboard/offers",headers:f.headers("viewer")})).json().items[0].consumed_quantity,7);
  }finally{await f.app.close();}
});

test("confirming volunteer help received releases its commitment without closing the SOS",async()=>{
  const f=await fixture();try{
    await f.upload(f.packet(f.supplier,3,{owner_node_id:Buffer.from(f.supplier.nodeId,"hex"),help_types:[6],availability:1,consent:f.consent}));
    const id=await f.proposal(f.requestId,f.supplier.nodeId,"helper",null);await f.review(id);
    await f.upload(f.decision(await f.status(id)));
    assert.equal((await f.upload(f.decision(await f.status(id,2,f.requester.nodeId),4,f.requester))).statusCode,200);
    assert.equal((await f.pg.query<any>("SELECT data FROM coordinator_resource_available WHERE entity_id=$1",[f.supplier.nodeId])).rows[0].data.availability,1);
    assert.equal((await f.pg.query<any>("SELECT state FROM sos_reports WHERE request_id=$1",[f.requestId])).rows[0].state,1);
  }finally{await f.app.close();}
});

test("competing acceptances cannot overcommit; a requester cancellation releases only active reservations",async()=>{
  const f=await fixture();try{
    const one=await f.proposal();const request2=randomUUID();
    assert.equal((await f.upload(f.packet(f.other,1,{request_id:request2,category:3,description:"Another water request",consent:f.consent}))).statusCode,200);
    const two=await f.proposal(request2);
    await f.review(one);await f.review(two);
    const decisions=[f.decision(await f.status(one)),f.decision(await f.status(two))];
    const results=await Promise.all(decisions.map(p=>f.upload(p)));assert.ok(results.every(r=>r.statusCode===200),results.map(r=>r.body).join("\n"));
    const cases=(await f.pg.query<any>("SELECT * FROM assistance_cases ORDER BY proposal_id")).rows;
    assert.equal(cases.filter(c=>c.state===2).length,1);assert.equal(cases.filter(c=>c.state===4).length,1);
    const winner=cases.find(c=>c.state===2)!;
    const actor=winner.request_id===f.requestId?f.requester:f.other;
    const original=winner.request_id===f.requestId?f.original:(await f.pg.query<any>("SELECT immutable_envelope,origin_signature FROM sos_reports WHERE request_id=$1",[request2])).rows[0];
    f.advance();
    const close=createPacket(actor,2,{request_id:winner.request_id,state:5,original_sos_envelope:winner.request_id===f.requestId?Buffer.from(f.original.immutable_envelope_bytes,"base64url"):original.immutable_envelope,
      original_sos_signature:winner.request_id===f.requestId?Buffer.from(f.original.signature,"base64url"):original.origin_signature},
        {packetId:randomUUID(),eventId:randomUUID(),createdAtMs:f.clock(),expiresAtMs:decode("ImmutableEnvelopeV1",winner.request_id===f.requestId?Buffer.from(f.original.immutable_envelope_bytes,"base64url"):original.immutable_envelope).expires_at_ms});
    assert.equal((await f.upload(close)).statusCode,200);
    assert.equal((await f.pg.query<any>("SELECT * FROM assistance_cases WHERE state=2")).rows.length,0);
    const balance=(await f.app.inject({url:"/v1/dashboard/offers",headers:f.headers("viewer")})).json().items[0];assert.equal(balance.available_quantity,10);
  }finally{await f.app.close();}
});

test("withdrawal cancels invitations but preserves commitments; owner release and expiry are durable signed transitions",async()=>{
  const f=await fixture();try{
    const id=await f.proposal();await f.review(id);const invite=await f.status(id);
    assert.equal((await f.upload(f.decision(invite))).statusCode,200);
    f.advance();const original=Buffer.from(f.offer.immutable_envelope_bytes,"base64url");
    const update=(state:number,quantity:number,unit="litres")=>createPacket(f.supplier,6,{offer_id:f.offerId,owner_node_id:Buffer.from(f.supplier.nodeId,"hex"),state,quantity,unit,coarse_area:"",available_until_ms:decode("ImmutableEnvelopeV1",original).expires_at_ms,
      consent:f.consent,original_offer_envelope:original,original_offer_signature:Buffer.from(f.offer.signature,"base64url")},{packetId:randomUUID(),eventId:randomUUID(),createdAtMs:f.clock(),expiresAtMs:decode("ImmutableEnvelopeV1",original).expires_at_ms});
    assert.equal((await f.upload(update(1,5))).statusCode,409);
    assert.equal((await f.upload(update(1,10,"bottles"))).statusCode,409);
    const withdrawn=update(2,10);assert.equal((await f.upload(withdrawn)).statusCode,200);
    assert.equal((await f.pg.query<any>("SELECT state FROM assistance_cases WHERE proposal_id=$1",[id])).rows[0].state,2);
    const proof=await f.status(id,2);assert.equal((await f.upload(f.decision(proof,3))).statusCode,200);
    await f.status(id,4,f.requester.nodeId);
    const late=f.decision(invite);assert.equal((await f.upload(late)).statusCode,409);
  }finally{await f.app.close();}
});

test("declines reserve nothing, stale invitations expire, and unknown stock cannot be approved",async()=>{
  const f=await fixture();try{
    const id=await f.proposal();await f.review(id);
    assert.equal((await f.upload(f.decision(await f.status(id),2))).statusCode,200);
    await f.status(id,3,f.requester.nodeId);
    const request=randomUUID();await f.upload(f.packet(f.other,1,{request_id:request,category:3,description:"Need help",consent:f.consent}));
    const expiring=await f.proposal(request);await f.review(expiring);const proof=await f.status(expiring);
    f.advance(3600001);
    await f.app.inject({url:"/v1/dashboard/assistance",headers:f.headers("viewer")});
    await f.status(expiring,5,f.other.nodeId);
    assert.equal((await f.upload(f.decision(proof))).statusCode,400);
    const unknown=randomUUID();await f.upload(f.packet(f.supplier,5,{offer_id:unknown,owner_node_id:Buffer.from(f.supplier.nodeId,"hex"),resource_type:1,consent:f.consent}));
    const noStock=await f.proposal(f.requestId,unknown,"offer",null);assert.equal((await f.review(noStock)).statusCode,409);
    const rejected=await f.review(noStock,"reject");assert.equal(rejected.statusCode,200);
    assert.equal((await f.pg.query("SELECT * FROM assistance_events WHERE proposal_id=$1",[noStock])).rows.length,0);
  }finally{await f.app.close();}
});

test("volunteer commitments exclude double booking and become available after requester resolution",async()=>{
  const f=await fixture();try{
    await f.upload(f.packet(f.supplier,3,{owner_node_id:Buffer.from(f.supplier.nodeId,"hex"),help_types:[6],availability:1,consent:f.consent}));
    const id=await f.proposal(f.requestId,f.supplier.nodeId,"helper",null);await f.review(id);
    assert.equal((await f.upload(f.decision(await f.status(id)))).statusCode,200);
    const busy=(await f.pg.query<any>("SELECT data FROM coordinator_resource_available WHERE entity_id=$1",[f.supplier.nodeId])).rows[0];
    assert.equal(busy.data.availability,2);
    const request=randomUUID();await f.upload(f.packet(f.other,1,{request_id:request,category:3,description:"Need water help",consent:f.consent}));
    const other=await f.proposal(request,f.supplier.nodeId,"helper",null);assert.equal((await f.review(other)).statusCode,409);
    f.advance();await f.upload(f.closure(6));
    const free=(await f.pg.query<any>("SELECT data FROM coordinator_resource_available WHERE entity_id=$1",[f.supplier.nodeId])).rows[0];
    assert.equal(free.data.availability,1);assert.equal((await f.review(other)).statusCode,200);
  }finally{await f.app.close();}
});

test("failed invitation or confirmation storage rolls back review, decision and reservations together",async()=>{
  const f=await fixture();try{
    const id=await f.proposal();f.failStatus(true);
    const failure=await f.review(id);assert.equal(failure.statusCode,503);assert.equal(failure.json().retryable,true);
    assert.equal((await f.pg.query("SELECT * FROM assistance_cases")).rows.length,0);
    f.failStatus(false);await f.review(id);const decision=f.decision(await f.status(id));
    f.failStatus(true);assert.equal((await f.upload(decision,"atomic-retry")).statusCode,503);
    assert.equal((await f.pg.query<any>("SELECT state FROM assistance_cases WHERE proposal_id=$1",[id])).rows[0].state,1);
    assert.equal((await f.pg.query("SELECT * FROM assistance_decisions")).rows.length,0);
    assert.equal((await f.pg.query("SELECT * FROM sync_uploads WHERE idempotency_key='atomic-retry'")).rows.length,0);
    f.failStatus(false);assert.equal((await f.upload(decision,"atomic-retry")).statusCode,200);
    await f.status(id,2,f.requester.nodeId);
  }finally{await f.app.close();}
});

test("assistance state, signed outbox and decision deduplication survive a database and server restart",async()=>{
  const path=await mkdtemp(join(tmpdir(),"ghost-assistance-"));const f=await fixture(path);
  let closed=false;let restored:ReturnType<typeof buildApp>|undefined;
  try{
    const id=await f.proposal();await f.review(id);
    assert.equal((await f.upload(f.decision(await f.status(id)))).statusCode,200);
    // Simulate the constraint installed by the previous server, then run its upgrade.
    await f.pg.query("ALTER TABLE assistance_cases DROP CONSTRAINT assistance_cases_state_check");
    await f.pg.query("ALTER TABLE assistance_cases ADD CONSTRAINT assistance_cases_state_check CHECK(state BETWEEN 1 AND 6)");
    await f.pg.transaction(c=>initializeAssistance(c));
    const decision=f.decision(await f.status(id,2,f.requester.nodeId),4,f.requester);
    assert.equal((await f.upload(decision)).statusCode,200);
    const before=(await f.app.inject({url:"/v1/sync/download?cursor=0&limit=50",headers:f.headers()})).json();
    await f.app.close();closed=true;
    const pg=new PGlite(path);const database:Database={query:(q,v)=>pg.query(q,v),transaction:work=>pg.transaction(c=>work(c)),close:()=>pg.close()};
    restored=buildApp({database,cloudSigningKeyPem:f.cloudPem,now:f.clock,
      principals:[{id:"gateway",role:"gateway",token_sha256:tokenHash(f.tokens.gateway),gateway_node_id:f.other.nodeId}]});
    const after=await restored.inject({url:"/v1/sync/download?cursor=0&limit=50",headers:f.headers()});
    assert.equal(after.statusCode,200);assert.deepEqual(after.json(),before);
    const duplicate=await restored.inject({method:"POST",url:"/v1/sync/upload",headers:f.headers(),payload:{idempotency_key:"after-restart",packets:[decision]}});
    assert.equal(duplicate.statusCode,200,duplicate.body);
    assert.equal((await pg.query<any>("SELECT state FROM assistance_cases WHERE proposal_id=$1",[id])).rows[0].state,7);
    assert.equal((await pg.query("SELECT * FROM assistance_decisions")).rows.length,2);
    const sources=(await pg.query<any>("SELECT data FROM coordinator_resource_available WHERE entity_id=$1",[f.offerId])).rows;
    assert.equal(sources[0].data.quantity,3);
  }finally{if(restored)await restored.close();if(!closed)await f.app.close();await rm(path,{recursive:true,force:true});}
});
