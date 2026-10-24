package org.ghost.gateway

import android.app.Application
import androidx.room.Room
import com.google.protobuf.ByteString
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import org.ghost.contracts.v1.*
import org.ghost.data.local.*
import org.ghost.domain.model.*
import org.ghost.protocol.*
import org.ghost.security.SigningIdentity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class GatewayAssistanceTest {
    private fun key()=KeyPairGenerator.getInstance("EC").apply{initialize(ECGenParameterSpec("secp256r1"))}.generateKeyPair().let{SigningIdentity(it.private,it.public)}
    private fun TestScope.until(condition:suspend ()->Boolean){repeat(1000){runCurrent();if(runBlocking{condition()})return;Thread.sleep(5)};fail("Assistance gateway did not reach expected state")}
    @Test fun downloadRequiresPinnedSignaturesAndCheckpointsOnlyAfterStorageThenUploadsOwnerDecision()=runTest {
        val context:Application=RuntimeEnvironment.getApplication();val db=Room.inMemoryDatabaseBuilder(context,GhostDatabase::class.java).build()
        val cloud=key();val owner=key();val requester=key();val now=1792860000000L
        val repo=LocalSosRepository(db,{owner},{now+1000});val id=UUID.randomUUID().toString();val requestId=UUID.randomUUID().toString();val resourceId=UUID.randomUUID().toString()
        fun status(state:Int,version:Int)=SignedPackets.create(cloud,16,AssistanceStatusV1.newBuilder().setProposalId(id).setVersion(version)
            .setRequestId(requestId).setResourceId(resourceId).setResourceKind(2).setQuantity(7).setUnit("litres")
            .setOwnerNodeId(ByteString.copyFrom(WireCodec.bytes(owner.nodeId.value))).setRequesterNodeId(ByteString.copyFrom(WireCodec.bytes(requester.nodeId.value)))
            .setState(state).setDescription("Need water").setDeadlineMs(now+3600000).build().toByteString(),now,ttl=3600000,destination=owner.nodeId.value)
        val invitation=status(1,1);val confirmation=status(2,2)
        val valid=AtomicBoolean(false);val accepted=AtomicBoolean(false);val downloads=AtomicInteger(0);val uploads=AtomicInteger(0)
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        val token="g".repeat(43)
        server.createContext("/v1/sync/download") {exchange->
            assertEquals("Bearer $token",exchange.requestHeaders.getFirst("Authorization"));downloads.incrementAndGet()
            val cursor=exchange.requestURI.query.substringAfter("cursor=").substringBefore("&")
            val packet=if(cursor=="0")invitation.takeIf{valid.get()}?:invitation.copy(signature="AA") else if(cursor=="1" && accepted.get())confirmation else null
            val next=if(packet==null)cursor else if(cursor=="0")"1" else "2"
            val response=Json.encodeToString(SyncDownloadResponse(cursor,next,listOfNotNull(packet))).encodeToByteArray()
            exchange.sendResponseHeaders(200,response.size.toLong());exchange.responseBody.use{it.write(response)}
        }
        server.createContext("/v1/sync/download-ack"){it.sendResponseHeaders(204,-1);it.close()}
        server.createContext("/v1/sync/upload") {exchange->
            val request=Json.decodeFromString<SyncUploadRequest>(exchange.requestBody.readBytes().decodeToString())
            val p=request.packets.single();val e=PacketValidator(cloud.publicKey.encoded).verify(p,now+1000,forCloud=true).envelope
            assertEquals(17,e.kindValue);uploads.incrementAndGet();accepted.set(true)
            val ack=SignedPackets.create(cloud,7,CloudAckV1.newBuilder().setOriginEventId(e.eventId).setStatusValue(1).setCloudRevision(5).setReceivedAtMs(now+1000).build().toByteString(),now+1000,destination=owner.nodeId.value)
            val response=Json.encodeToString(SyncUploadResponse(listOf(SyncUploadItemResult(EventId(e.eventId),CloudAckStatus.ACCEPTED,5)),listOf(ack))).encodeToByteArray()
            exchange.sendResponseHeaders(200,response.size.toLong());exchange.responseBody.use{it.write(response)}
        }
        server.start()
        val url="http://127.0.0.1:${server.address.port}"
        val config=MutableStateFlow<GatewayConfig?>(GatewayConfig.validated(url,token,true))
        val worker=CoroutineScope(backgroundScope.coroutineContext+Job(backgroundScope.coroutineContext[Job]))
        try {
            GatewaySync(worker,config,MutableStateFlow(true),HttpGatewayClient{config.value},repo,cloud.publicKey.encoded)
            until{downloads.get()>0};runCurrent();assertEquals("0",repo.assistance.cursor(url));assertTrue(repo.assistance.observe().first().isEmpty())
            valid.set(true);advanceTimeBy(15001)
            until{repo.assistance.cursor(url)=="1"}
            assertEquals(1,repo.assistance.observe().first().single().state)
            repo.assistance.decide(id,1,1,cloud.publicKey.encoded)
            until{uploads.get()==1 && repo.cloudOutgoing().isEmpty()}
            advanceTimeBy(15001)
            until{repo.assistance.observe().first().single().state==2}
            assertEquals("2",repo.assistance.cursor(url));assertFalse(repo.assistance.observe().first().single().pending)
        } finally{worker.cancel();runCurrent();server.stop(0);db.close()}
    }
}
