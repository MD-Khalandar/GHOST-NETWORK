import assert from "node:assert/strict";
import test from "node:test";
import { PGlite } from "@electric-sql/pglite";
import { generateKeyPairSync,randomUUID,randomBytes } from "node:crypto";
import { buildApp } from "./app.js";
import { SigningIdentity,tokenHash } from "./security.js";
import { createPacket,decode,validatePacket,type WirePacket } from "./protocol.js";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import type { Database } from "./database.js";
import { AssistanceService,initializeAssistance } from "./assistance.js";
import { CoordinatorStore,initializeCoordinator } from "./coordinator/store.js";
import { AssistanceTeam } from "./coordinator/team.js";
import type { MatchContext } from "./coordinator/types.js";

const key=()=>SigningIdentity.fromPem(generateKeyPairSync("ec",{namedCurve:"prime256v1"}).privateKey.export({format:"pem",type:"pkcs8"}).toString());
async function fixture(path?: string,manualReview=true,description="Need drinking water") {
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
  await app.ready();
  if(manualReview)assert.equal((await app.inject({method:"POST",url:"/v1/dashboard/coordination-settings",headers:headers("operator"),payload:{manual_review:true}})).statusCode,200);
  const packet=(owner:SigningIdentity,kind:number,payload:Record<string,unknown>)=>createPacket(owner,kind,payload,{packetId:randomUUID(),eventId:randomUUID(),createdAtMs:clock,expiresAtMs:clock+7200000});
  const upload=(p:WirePacket,id:string=randomUUID())=>app.inject({method:"POST",url:"/v1/sync/upload",headers:headers(),payload:{idempotency_key:id,packets:[p]}});
  const consent={public_mesh_allowed:true,cloud_processing_allowed:true};
  const requestId=randomUUID(),offerId=randomUUID();
  const original=packet(requester,1,{request_id:requestId,category:3,description,coarse_area:"Station",consent});
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
    const found=rows.map(r=>r.packet as WirePacket).find(p=>{const e=decode("ImmutableEnvelopeV1",Buffer.from(p.immutable_envelope_bytes,"base64url"));return e.kind===16 && decode("AssistanceStatusV1",e.payload_bytes).state===state;});
    assert.ok(found);validatePacket(found,{nowMs:clock,trustedCloudNodeId:cloud.nodeId});return found;
  }
  const decision=(proof:WirePacket,choice=1,owner=supplier)=>{
    const e=decode("ImmutableEnvelopeV1",Buffer.from(proof.immutable_envelope_bytes,"base64url"));const p=decode("AssistanceStatusV1",e.payload_bytes);
    return createPacket(owner,17,{proposal_id:p.proposal_id,version:p.version,decision:choice,status_envelope:Buffer.from(proof.immutable_envelope_bytes,"base64url"),status_signature:Buffer.from(proof.signature,"base64url")},{packetId:randomUUID(),eventId:randomUUID(),createdAtMs:clock,expiresAtMs:e.expires_at_ms});
  };
  const closure=(state:number)=>createPacket(requester,2,{request_id:requestId,state,original_sos_envelope:Buffer.from(original.immutable_envelope_bytes,"base64url"),original_sos_signature:Buffer.from(original.signature,"base64url")},{packetId:randomUUID(),eventId:randomUUID(),createdAtMs:clock,expiresAtMs:decode("ImmutableEnvelopeV1",Buffer.from(original.immutable_envelope_bytes,"base64url")).expires_at_ms});
  return {pg,database,app,cloud,cloudPem,tokens,failStatus:(value:boolean)=>{failStatusWrite=value;},supplier,requester,other,headers,packet,upload,proposal,review,status,decision,closure,original,offer,offerId,requestId,consent,
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
    assert.equal(after.reserved_quantity,0);assert.equal(after.consumed_quantity,0);assert.equal(after.available_quantity,10);
    await f.status(id,6,f.requester.nodeId);
  }finally{await f.app.close();}
});

test("resolving releases outstanding invitations and commitments, preserves receipts and makes stock reusable",async()=>{
  const f=await fixture();try{
    const received=await f.proposal();await f.review(received);
    await f.upload(f.decision(await f.status(received)));
    await f.upload(f.decision(await f.status(received,2,f.requester.nodeId),4,f.requester));
    const releasedOffer=randomUUID(),pendingOffer=randomUUID();
    let original:WirePacket|undefined;
    for(const offerId of [releasedOffer,pendingOffer]){
      const offer=f.packet(f.supplier,5,{offer_id:offerId,owner_node_id:Buffer.from(f.supplier.nodeId,"hex"),resource_type:1,quantity:10,unit:"litres",consent:f.consent});
      assert.equal((await f.upload(offer)).statusCode,200);
      if(offerId===releasedOffer)original=offer;
    }
    const committed=await f.proposal(f.requestId,releasedOffer),pending=await f.proposal(f.requestId,pendingOffer);
    await f.review(committed);await f.review(pending);
    await f.upload(f.decision(await f.status(committed)));
    const oldCommitment=await f.status(committed,2,f.requester.nodeId),oldInvitation=await f.status(pending);
    f.advance();const closure=f.closure(6);
    f.failStatus(true);
    assert.equal((await f.upload(closure,"resolve-retry")).statusCode,503);
    assert.equal((await f.pg.query<any>("SELECT state FROM assistance_cases WHERE proposal_id=$1",[committed])).rows[0].state,2);
    f.failStatus(false);
    assert.equal((await f.upload(closure,"resolve-retry")).statusCode,200);
    const count=(await f.pg.query("SELECT * FROM assistance_events")).rows.length;
    assert.equal((await f.upload(closure)).statusCode,200);
    assert.equal((await f.pg.query("SELECT * FROM assistance_events")).rows.length,count);
    await f.status(received,7,f.requester.nodeId);await f.status(committed,6);await f.status(pending,4);
    const balance=async(id:string)=>(await f.app.inject({url:"/v1/dashboard/offers",headers:f.headers("viewer")})).json().items.find((o:any)=>o.offer_id===id);
    assert.equal((await balance(f.offerId)).consumed_quantity,7);
    for(const id of [releasedOffer,pendingOffer]){
      const stock=await balance(id);
      assert.equal(stock.reserved_quantity,0);assert.equal(stock.consumed_quantity,0);assert.equal(stock.available_quantity,10);
    }
    assert.equal((await f.upload(f.decision(oldCommitment,4,f.requester))).statusCode,409);
    assert.equal((await f.upload(f.decision(oldInvitation))).statusCode,409);
    // Resolution no longer locks the released offer's quantity or unit.
    const envelope=decode("ImmutableEnvelopeV1",Buffer.from(original!.immutable_envelope_bytes,"base64url"));
    const edit=createPacket(f.supplier,6,{offer_id:releasedOffer,owner_node_id:Buffer.from(f.supplier.nodeId,"hex"),state:1,quantity:10,unit:"bottles",coarse_area:"",consent:f.consent,
      available_until_ms:envelope.expires_at_ms,original_offer_envelope:Buffer.from(original!.immutable_envelope_bytes,"base64url"),original_offer_signature:Buffer.from(original!.signature,"base64url")},
      {packetId:randomUUID(),eventId:randomUUID(),createdAtMs:f.clock(),expiresAtMs:envelope.expires_at_ms});
    const edited=await f.upload(edit);assert.equal(edited.statusCode,200,edited.body);
    const request=randomUUID();await f.upload(f.packet(f.other,1,{request_id:request,category:3,description:"Need drinking water",consent:f.consent}));
    const context=await new CoordinatorStore(f.database).context(request,f.clock(),"supply");
    assert.equal(context.resources.find(r=>r.resource_id===releasedOffer)!.quantity,10);
    const next=await f.proposal(request,releasedOffer,"offer",10);
    assert.equal((await f.review(next)).statusCode,200);
    assert.equal((await f.upload(f.decision(await f.status(next)))).statusCode,200);
    await f.status(next,2,f.other.nodeId);
    assert.equal((await balance(releasedOffer)).reserved_quantity,10);
    assert.equal((await balance(releasedOffer)).available_quantity,0);
    assert.equal((await balance(f.offerId)).consumed_quantity,7);
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

test("the same signed supply offer can fulfil a remaining need after partial receipt, including upgraded databases",async()=>{
  const f=await fixture(undefined,true,"Need 10 litres of drinking water");try{
    const store=new CoordinatorStore(f.database),inputs:MatchContext[]=[];
    const team=new AssistanceTeam(store,{available:true,name:"test-partial-delivery",suggest:async context=>{
      inputs.push(context);
      const offer=context.resources.find(r=>r.resource_id===f.offerId)!;
      const received=context.follow_up!.arrangements.filter(a=>a.state===7 && a.resource_type===1 && a.unit==="litres")
        .reduce((sum,a)=>sum+(a.quantity??0),0);
      return {matches:[{request_id:f.requestId,resource_kind:"offer",resource_id:offer.resource_id,
        quantity:Math.min(received===0?7:10-received,offer.quantity!),explanation:"Deliver the remaining requested drinking water"}]};
    }},f.clock);
    const first=(await team.run(f.requestId)).proposal_ids[0];assert.ok(first);
    assert.equal(inputs.length,1);
    // Recreate the previous proposal schema and migrate with a real pending proposal.
    await f.pg.query("DROP INDEX coordinator_proposal_delivery_round");
    await f.pg.query("ALTER TABLE coordinator_proposals DROP COLUMN delivery_round");
    await f.pg.query("ALTER TABLE coordinator_proposals ADD CONSTRAINT coordinator_proposals_request_event_id_resource_event_id_key UNIQUE(request_event_id,resource_event_id)");
    await initializeCoordinator(f.database);
    assert.equal((await f.pg.query<any>("SELECT delivery_round FROM coordinator_proposals WHERE proposal_id=$1",[first])).rows[0].delivery_round,0);
    await team.run(f.requestId);assert.equal(inputs.length,1); // Pending review blocks repetition.
    const service=new AssistanceService(f.database,f.cloud,f.clock);
    await service.setReviewMode(false,"operator");await service.autoReview();
    await team.run(f.requestId);assert.equal(inputs.length,1); // Invitation still needs acceptance.
    assert.equal((await f.upload(f.decision(await f.status(first)))).statusCode,200);
    await team.run(f.requestId);assert.equal(inputs.length,1); // Active delivery blocks repetition.
    const receipt=f.decision(await f.status(first,2,f.requester.nodeId),4,f.requester);
    assert.equal((await f.upload(receipt)).statusCode,200);
    assert.equal((await f.upload(receipt)).statusCode,200); // Duplicate receipt cannot open another round.
    const next=await store.context(f.requestId,f.clock(),"supply");
    assert.equal(next.resources.length,1);assert.equal(next.resources[0].resource_id,f.offerId);
    assert.equal(next.resources[0].source.event_id,decode("ImmutableEnvelopeV1",Buffer.from(f.offer.immutable_envelope_bytes,"base64url")).event_id);
    assert.equal(next.resources[0].quantity,3);assert.equal(next.resources[0].delivery_round,1);
    assert.equal(next.follow_up!.arrangements[0].resource_type,1);assert.equal(next.follow_up!.arrangements[0].quantity,7);
    assert.equal(await store.checkProposal(first,f.clock()),"stale");
    const second=(await team.run(f.requestId)).proposal_ids[0];assert.ok(second);assert.notEqual(second,first);
    assert.equal(inputs.length,2);
    const proposals=(await f.pg.query<any>("SELECT proposal_id,delivery_round,quantity FROM coordinator_proposals ORDER BY delivery_round")).rows;
    assert.deepEqual(proposals.map(p=>[p.delivery_round,p.quantity]),[[0,7],[1,3]]);
    assert.equal(await store.checkProposal(second,f.clock()),"current");
    await team.run(f.requestId);assert.equal(inputs.length,2);
    await Promise.all([service.autoReview(),service.autoReview()]);
    assert.equal((await f.pg.query("SELECT * FROM assistance_cases")).rows.length,2);
    assert.equal((await f.pg.query("SELECT * FROM assistance_events WHERE proposal_id=$1",[second])).rows.length,2);
    assert.equal((await f.upload(f.decision(await f.status(second)))).statusCode,200);
    const reserved=(await f.app.inject({url:"/v1/dashboard/offers",headers:f.headers("viewer")})).json().items[0];
    assert.equal(reserved.consumed_quantity,7);assert.equal(reserved.reserved_quantity,3);assert.equal(reserved.available_quantity,0);
    assert.equal((await f.upload(f.decision(await f.status(second,2,f.requester.nodeId),4,f.requester))).statusCode,200);
    await team.run(f.requestId);assert.equal(inputs.length,2);
    assert.equal((await store.context(f.requestId,f.clock(),"supply")).resources.length,0);
    const final=(await f.app.inject({url:"/v1/dashboard/offers",headers:f.headers("viewer")})).json().items[0];
    assert.equal(final.consumed_quantity,10);assert.equal(final.reserved_quantity,0);assert.equal(final.available_quantity,0);
    assert.equal((await f.pg.query<any>("SELECT data FROM resource_events WHERE entity_id=$1",[f.offerId])).rows[0].data.quantity,10);
    assert.equal((await f.pg.query<any>("SELECT state FROM sos_reports WHERE request_id=$1",[f.requestId])).rows[0].state,1);
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

test("automatic invitations are the default, deduplicate across workers and still require owner acceptance",async()=>{
  const f=await fixture(undefined,false);try{
    const settings=await f.app.inject({url:"/v1/dashboard/coordination-settings",headers:f.headers("viewer")});
    assert.equal(settings.statusCode,200);assert.equal(settings.json().manual_review,false);
    assert.equal(settings.headers["cache-control"],"no-store");
    const id=await f.proposal();
    const service=new AssistanceService(f.database,f.cloud,f.clock);
    await Promise.all([service.autoReview(),service.autoReview()]);
    const row=(await f.pg.query<any>("SELECT * FROM assistance_cases WHERE proposal_id=$1",[id])).rows[0];
    assert.equal(row.state,1);assert.equal(row.operator_id,"ai:auto");
    assert.equal((await f.pg.query("SELECT * FROM assistance_events")).rows.length,2);
    await service.autoReview();assert.equal((await f.pg.query("SELECT * FROM assistance_events")).rows.length,2);
    const balance=(await f.app.inject({url:"/v1/dashboard/offers",headers:f.headers("viewer")})).json().items[0];
    assert.equal(balance.reserved_quantity,0);assert.equal(balance.available_quantity,10);
    const invitation=await f.status(id);assert.match(decode("AssistanceStatusV1",decode("ImmutableEnvelopeV1",Buffer.from(invitation.immutable_envelope_bytes,"base64url")).payload_bytes).reason,/automatically/);
    assert.equal((await f.upload(f.decision(invitation))).statusCode,200);
    await f.status(id,2,f.requester.nodeId);
  }finally{await f.app.close();}
});

test("operator-only manual toggle pauses new invitations and returning to automatic drains pending suggestions",async()=>{
  const f=await fixture();try{
    const url="/v1/dashboard/coordination-settings";
    for(const role of ["viewer","gateway"] as const)
      assert.equal((await f.app.inject({method:"POST",url,headers:f.headers(role),payload:{manual_review:false}})).statusCode,403);
    for(const payload of [{manual_review:"false"},{manual_review:false,role:"operator"},{}])
      assert.equal((await f.app.inject({method:"POST",url,headers:f.headers("operator"),payload})).statusCode,400);
    const id=await f.proposal();const service=new AssistanceService(f.database,f.cloud,f.clock);
    await service.autoReview();assert.equal((await f.pg.query("SELECT * FROM assistance_cases")).rows.length,0);
    const enabled=await f.app.inject({method:"POST",url,headers:f.headers("operator"),payload:{manual_review:false}});
    assert.equal(enabled.statusCode,200);assert.equal(enabled.json().manual_review,false);
    for(let attempt=0;attempt<100;attempt++) {
      if((await f.pg.query("SELECT * FROM assistance_cases")).rows.length)break;
      await new Promise(resolve=>setTimeout(resolve,10));
    }
    await f.status(id);
    await service.setReviewMode(true,"operator");
    await f.upload(f.packet(f.other,1,{request_id:randomUUID(),category:3,description:"Need water",consent:f.consent}));
    const otherRequest=(await f.pg.query<any>("SELECT request_id FROM sos_reports WHERE sender_node_id=$1",[f.other.nodeId])).rows[0].request_id;
    await f.proposal(otherRequest);await service.autoReview();
    assert.equal((await f.pg.query("SELECT * FROM assistance_cases")).rows.length,1);
    assert.equal((await service.reviewMode()).manual_review,true);
  }finally{await f.app.close();}
});

test("unknown-stock suggestions cannot block eligible automatic invitations after the batch limit",async()=>{
  const f=await fixture();try{
    for(let index=0;index<21;index++) {
      const offerId=randomUUID();f.advance(1);
      await f.upload(f.packet(f.supplier,5,{offer_id:offerId,owner_node_id:Buffer.from(f.supplier.nodeId,"hex"),resource_type:1,unit:"litres",consent:f.consent}));
      await f.proposal(f.requestId,offerId,"offer",null);
    }
    f.advance(1);const valid=await f.proposal();
    const service=new AssistanceService(f.database,f.cloud,f.clock);
    await service.setReviewMode(false,"operator");await service.autoReview();
    assert.equal((await f.pg.query<any>("SELECT proposal_id FROM assistance_cases")).rows[0].proposal_id,valid);
    assert.equal((await f.pg.query("SELECT * FROM assistance_cases")).rows.length,1);
  }finally{await f.app.close();}
});

test("automatic recovery expires old commitments before checking availability without phone polling",async()=>{
  const f=await fixture();try{
    const profile=()=>f.packet(f.supplier,3,{owner_node_id:Buffer.from(f.supplier.nodeId,"hex"),help_types:[6],availability:1,consent:f.consent});
    await f.upload(profile());
    const old=await f.proposal(f.requestId,f.supplier.nodeId,"helper",null);await f.review(old);
    await f.upload(f.decision(await f.status(old)));
    f.advance(1000);await f.upload(profile());
    const next=await f.proposal(f.requestId,f.supplier.nodeId,"helper",null);
    f.advance(3600001);
    const service=new AssistanceService(f.database,f.cloud,f.clock);
    await service.setReviewMode(false,"operator");await service.autoReview();
    assert.equal((await f.pg.query<any>("SELECT state FROM assistance_cases WHERE proposal_id=$1",[old])).rows[0].state,5);
    assert.equal((await f.pg.query<any>("SELECT state FROM assistance_cases WHERE proposal_id=$1",[next])).rows[0].state,1);
  }finally{await f.app.close();}
});

test("specialists follow signed decisions through alternatives, commitments, receipts, timeout and SOS closure",async()=>{
  const f=await fixture();try{
    const alternative=randomUUID();
    for(const actor of [f.supplier,f.other])
      await f.upload(f.packet(actor,3,{owner_node_id:Buffer.from(actor.nodeId,"hex"),help_types:[6],availability:1,consent:f.consent}));
    await f.upload(f.packet(f.other,5,{offer_id:alternative,owner_node_id:Buffer.from(f.other.nodeId,"hex"),resource_type:1,quantity:8,unit:"litres",consent:f.consent}));
    const inputs:MatchContext[]=[];const store=new CoordinatorStore(f.database);
    const team=new AssistanceTeam(store,{available:true,name:"test-specialists",suggest:async context=>{
      inputs.push(context);
      assert.ok(context.resources.every(r=>r.kind===(context.specialist==="helper"?"helper":"offer")));
      if(context.specialist==="supply" && context.follow_up!.arrangements.some(a=>a.state===7))return {matches:[]};
      const r=context.resources.find(r=>r.resource_id===(context.specialist==="helper"?f.supplier.nodeId:f.offerId))??context.resources[0];
      return {matches:[{request_id:f.requestId,resource_kind:r.kind,resource_id:r.resource_id,
        quantity:r.kind==="helper"?null:7,explanation:"Reported capability; access still needs owner confirmation"}]};
    }},f.clock);
    assert.equal((await team.run(f.requestId)).proposal_ids.length,2);assert.equal(inputs.length,2);
    const service=new AssistanceService(f.database,f.cloud,f.clock);
    await service.setReviewMode(false,"operator");await service.autoReview();
    await team.run(f.requestId);assert.equal(inputs.length,2);
    let plans=await store.followups(f.clock());assert.ok(plans.every(p=>p.status==="awaiting_owner"));
    const initial=(await f.pg.query<any>("SELECT proposal_id,resource_kind FROM assistance_cases")).rows;
    const helper=initial.find(r=>r.resource_kind===1).proposal_id,supply=initial.find(r=>r.resource_kind===2).proposal_id;
    await f.upload(f.decision(await f.status(helper)));
    await f.upload(f.decision(await f.status(supply),2));
    await team.run(f.requestId);assert.equal(inputs.length,3);
    assert.equal(inputs[2].specialist,"supply");assert.equal(inputs[2].follow_up!.arrangements[0].state,3);
    assert.ok(inputs[2].resources.every(r=>r.resource_id!==f.offerId));
    await service.autoReview();
    const replacement=(await f.pg.query<any>("SELECT proposal_id FROM assistance_cases WHERE resource_id=$1",[alternative])).rows[0].proposal_id;
    await f.upload(f.decision(await f.status(replacement,1,f.other.nodeId),1,f.other));
    await team.run(f.requestId);assert.equal(inputs.length,3);
    await f.upload(f.decision(await f.status(replacement,2,f.requester.nodeId),4,f.requester));
    await team.run(f.requestId);assert.equal(inputs.length,4);
    plans=await store.followups(f.clock());
    assert.equal(plans.find(p=>p.specialist==="supply")!.received_count,1);
    assert.equal(plans.find(p=>p.specialist==="helper")!.status,"committed");
    await f.upload(f.decision(await f.status(helper,2),3));
    await team.run(f.requestId);assert.equal(inputs.length,5);assert.equal(inputs[4].specialist,"helper");
    await service.autoReview();f.advance(3600001);await service.autoReview();
    await team.run(f.requestId);assert.equal(inputs.length,5);
    assert.equal((await store.followups(f.clock())).find(p=>p.specialist==="helper")!.status,"no_candidates");
    assert.equal((await store.followups(f.clock())).find(p=>p.specialist==="supply")!.status,"no_match");
    await f.upload(f.closure(6));await store.closeTasks(f.clock());
    assert.equal((await team.run(f.requestId)).code,"REQUEST_UNAVAILABLE");assert.equal(inputs.length,5);
    assert.ok((await store.followups(f.clock())).every(p=>p.status==="closed"));
  }finally{await f.app.close();}
});

test("private chat validates participants, stores ciphertext once and keeps legacy downloads compatible",async()=>{
  const f=await fixture();try{
    const id=await f.proposal();await f.review(id);await f.upload(f.decision(await f.status(id)));
    const proofA=await f.status(id,2),proofB=await f.status(id,2,f.requester.nodeId);
    const rsa=generateKeyPairSync("rsa",{modulusLength:2048}).publicKey.export({type:"spki",format:"der"});
    const base=(proof:WirePacket,type:number)=>({proposal_id:id,type,status_envelope:Buffer.from(proof.immutable_envelope_bytes,"base64url"),status_signature:Buffer.from(proof.signature,"base64url")});
    const chat=(actor:SigningIdentity,destination:string,payload:Record<string,unknown>,messageId=randomUUID(),expiry=f.clock()+3600000)=>
      createPacket(actor,18,payload,{packetId:randomUUID(),eventId:messageId,createdAtMs:f.clock(),expiresAtMs:expiry,destinationNodeId:destination});
    const key=chat(f.requester,f.supplier.nodeId,{...base(proofB,1),rsa_public_key:rsa});
    const wrong=chat(f.other,f.supplier.nodeId,{...base(proofB,1),rsa_public_key:rsa});
    assert.equal((await f.upload(wrong)).statusCode,400);
    assert.equal((await f.upload(key)).statusCode,200);
    const messageId=randomUUID();
    const payload={...base(proofA,2),message_id:messageId,recipient_key_envelope:Buffer.from(key.immutable_envelope_bytes,"base64url"),recipient_key_signature:Buffer.from(key.signature,"base64url"),
      nonce:randomBytes(12),ciphertext:randomBytes(50),wrapped_key:randomBytes(256),sender_wrapped_key:randomBytes(256)};
    const message=chat(f.supplier,f.requester.nodeId,payload,messageId);
    f.failStatus(true);
    assert.equal((await f.upload(message,"chat-retry")).statusCode,503);
    assert.equal((await f.pg.query("SELECT * FROM assistance_decisions WHERE event_id=$1",[messageId])).rows.length,0);
    f.failStatus(false);
    assert.equal((await f.upload(message,"chat-retry")).statusCode,200);
    assert.equal((await f.upload(message)).statusCode,200);
    assert.equal((await f.pg.query("SELECT * FROM assistance_events WHERE kind=18")).rows.length,2);
    const legacy=(await f.app.inject({url:"/v1/sync/download?cursor=0&limit=50",headers:f.headers()})).json();
    assert.ok(legacy.packets.every((p:WirePacket)=>decode("ImmutableEnvelopeV1",Buffer.from(p.immutable_envelope_bytes,"base64url")).kind===16));
    const modern=await f.app.inject({url:"/v1/sync/download?cursor=0&limit=50&private_chat=1",headers:f.headers()});
    assert.equal(modern.statusCode,200);assert.equal(modern.json().packets.length,legacy.packets.length+2);
    assert.equal((await f.app.inject({url:"/v1/sync/download?private_chat=1",headers:f.headers("viewer")})).statusCode,403);
    const outsiderRecipient=chat(f.supplier,f.other.nodeId,payload,messageId);
    assert.equal((await f.upload(outsiderRecipient)).statusCode,400);
    const inventedKey=chat(f.supplier,f.requester.nodeId,{...payload,recipient_key_signature:Buffer.alloc(70,1)},messageId);
    assert.equal((await f.upload(inventedKey)).statusCode,400);
    f.advance();await f.upload(f.closure(5));
    const lateId=randomUUID();assert.equal((await f.upload(chat(f.supplier,f.requester.nodeId,{...payload,message_id:lateId},lateId,
      decode("ImmutableEnvelopeV1",Buffer.from(key.immutable_envelope_bytes,"base64url")).expires_at_ms))).statusCode,409);
    // A receipt can return after closure without reopening chat or an SOS.
    const receipt=chat(f.requester,f.supplier.nodeId,{...base(proofA,3),message_id:messageId},randomUUID(),f.clock()+86400000);
    assert.equal((await f.upload(receipt)).statusCode,200);
    assert.equal((await f.pg.query<any>("SELECT state FROM assistance_cases WHERE proposal_id=$1",[id])).rows[0].state,4);
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
      principals:[{id:"gateway",role:"gateway",token_sha256:tokenHash(f.tokens.gateway),gateway_node_id:f.other.nodeId},
        {id:"viewer",role:"viewer",token_sha256:tokenHash(f.tokens.viewer)}]});
    const after=await restored.inject({url:"/v1/sync/download?cursor=0&limit=50",headers:f.headers()});
    assert.equal(after.statusCode,200);assert.deepEqual(after.json(),before);
    const duplicate=await restored.inject({method:"POST",url:"/v1/sync/upload",headers:f.headers(),payload:{idempotency_key:"after-restart",packets:[decision]}});
    assert.equal(duplicate.statusCode,200,duplicate.body);
    assert.equal((await pg.query<any>("SELECT state FROM assistance_cases WHERE proposal_id=$1",[id])).rows[0].state,7);
    assert.equal((await pg.query("SELECT * FROM assistance_decisions")).rows.length,2);
    assert.equal((await restored.inject({url:"/v1/dashboard/coordination-settings",headers:f.headers("viewer")})).json().manual_review,true);
    const sources=(await pg.query<any>("SELECT data FROM coordinator_resource_available WHERE entity_id=$1",[f.offerId])).rows;
    assert.equal(sources[0].data.quantity,3);
  }finally{if(restored)await restored.close();if(!closed)await f.app.close();await rm(path,{recursive:true,force:true});}
});
