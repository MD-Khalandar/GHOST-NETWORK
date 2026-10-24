package org.ghost.gateway

import android.app.Application
import androidx.room.Room
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import org.ghost.contracts.v1.CloudAckV1
import org.ghost.contracts.v1.ImmutableEnvelopeV1
import org.ghost.data.local.GhostDatabase
import org.ghost.data.local.LocalSosRepository
import org.ghost.domain.model.*
import org.ghost.protocol.SignedPackets
import org.ghost.protocol.WireCodec
import org.ghost.security.SigningIdentity
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real loopback HTTP, Room files and signatures. No deployed cloud or handset Keystore. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GatewaySyncTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val databases = mutableListOf<GhostDatabase>()
    private val names = mutableListOf<String>()
    private val servers = mutableListOf<HttpServer>()
    private val time = 1792860000000L
    private val token = "g".repeat(43)
    private fun key(): SigningIdentity = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        .generateKeyPair().let { SigningIdentity(it.private, it.public) }
    private fun open(name: String = "gateway-${UUID.randomUUID()}.db"): GhostDatabase {
        names.add(name)
        return Room.databaseBuilder(context, GhostDatabase::class.java, name)
            .addMigrations(GhostDatabase.MIGRATION_1_2, GhostDatabase.MIGRATION_2_3, GhostDatabase.MIGRATION_3_4, GhostDatabase.MIGRATION_4_5).build().also { databases.add(it) }
    }
    private suspend fun publish(repo: LocalSosRepository, public: Boolean = true, cloud: Boolean = true) = repo.publish(
        SosCreatePayload(RequestId(UUID.randomUUID().toString()), SosCategory.WATER, "Need water", consent = Consent(public, cloud))).getOrThrow()
    private fun server(cloud: SigningIdentity, mode: AtomicInteger, uploads: MutableList<SyncUploadRequest>): String {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/sync/upload") { exchange ->
            val request = Json.decodeFromString<SyncUploadRequest>(exchange.requestBody.readBytes().decodeToString())
            uploads.add(request)
            assertEquals("Bearer $token", exchange.requestHeaders.getFirst("Authorization"))
            val status: Int
            val body: String
            if (mode.get() == 503) {
                status = 503; body = """{"code":"STORAGE_UNAVAILABLE","retryable":true}"""
            } else {
                val envelope = ImmutableEnvelopeV1.parseFrom(WireCodec.unbase64(request.packets.single().immutableEnvelopeBytesBase64, 24576))
                val ack = SignedPackets.create(cloud, 7, CloudAckV1.newBuilder().setOriginEventId(envelope.eventId)
                    .setStatusValue(1).setCloudRevision(1).setReceivedAtMs(time).build().toByteString(), time,
                    destination = SigningIdentity.hex(envelope.senderNodeId.toByteArray()))
                status = 200
                body = Json.encodeToString(SyncUploadResponse(listOf(SyncUploadItemResult(EventId(envelope.eventId), CloudAckStatus.ACCEPTED, 1)),
                    listOf(if (mode.get() == 400) ack.copy(signature = "AA") else ack)))
            }
            val bytes = body.encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start(); servers.add(server)
        return "http://127.0.0.1:${server.address.port}"
    }
    private fun TestScope.until(condition: suspend () -> Boolean) {
        repeat(1000) {
            runCurrent()
            if (runBlocking { condition() }) return
            Thread.sleep(5)
        }
        fail("Gateway did not reach the expected state")
    }
    @After fun close() { servers.forEach { it.stop(0) }; databases.forEach { it.close() }; names.forEach { context.deleteDatabase(it) } }

    @Test fun gatewayUploadsOnlyConsentedReportsAndKeepsVerifiedProofAcrossRestart() = runTest {
        val origin = key(); val gatewayKey = key(); val cloud = key()
        val source = LocalSosRepository(open(), { origin }, { time })
        val name = "gateway-reopen-${UUID.randomUUID()}.db"; val db = open(name)
        val gateway = LocalSosRepository(db, { gatewayKey }, { time })
        runBlocking {
            publish(source, cloud = false); publish(source)
            source.outgoing(gatewayKey.nodeId.value).forEach { gateway.receiveSos(WireCodec.decode(it.wire), origin.nodeId.value) }
            publish(gateway, public = false)
        }
        val expected = runBlocking { gateway.cloudOutgoing().single() }
        val uploads = CopyOnWriteArrayList<SyncUploadRequest>()
        val settings = MutableStateFlow<GatewayConfig?>(GatewayConfig.validated(server(cloud, AtomicInteger(200), uploads), token, true))
        val workerScope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        GatewaySync(workerScope, settings, MutableStateFlow(true), HttpGatewayClient { settings.value }, gateway, cloud.publicKey.encoded)
        until { db.sos().event(expected.eventId)?.cloudAck != null }
        assertEquals(1, uploads.size)
        assertEquals(WireCodec.decode(expected.wire).immutableEnvelopeBytesBase64, uploads.single().packets.single().immutableEnvelopeBytesBase64)
        assertEquals(WireCodec.decode(expected.wire).signature, uploads.single().packets.single().signature)
        assertEquals("sos-${expected.packetId}", uploads.single().idempotencyKey)
        assertTrue(runBlocking { gateway.cloudOutgoing().isEmpty() })
        assertTrue(runBlocking { source.observeRequests().first().none { it.cloudState == LocalCloudState.ACCEPTED } }) // No radio connection exists in this HTTP-only test.
        val receipt = runBlocking { db.sos().event(expected.eventId)!!.cloudAck!! }
        // Earlier app versions saved only reports.cloudAck, without a return queue.
        db.openHelper.writableDatabase.execSQL("DELETE FROM cloud_returns")
        workerScope.cancel(); runCurrent(); db.close(); databases.remove(db)
        val restoredDb = open(name)
        val restored = LocalSosRepository(restoredDb, { gatewayKey }, { time + 1000 })
        runBlocking { restored.restoreCloudReturns(cloud.publicKey.encoded) }
        assertArrayEquals(receipt, runBlocking { restoredDb.sos().event(expected.eventId)!!.cloudAck })
        assertArrayEquals(expected.wire, runBlocking { restoredDb.sos().event(expected.eventId)!!.wire })
        assertTrue(runBlocking { restored.cloudOutgoing().isEmpty() })
        assertEquals(1, runBlocking { restored.outgoingCloudReturns(origin.nodeId.value).size })
        val requestId = runBlocking { restoredDb.sos().event(expected.eventId)!!.requestId }
        assertEquals(LocalCloudState.ACCEPTED, runBlocking { restored.observeRequests().first().first { it.requestId.value == requestId }.cloudState })
    }

    @Test fun temporaryServerFailureKeepsQueueAndRetryUsesTheSameIdempotencyKey() = runTest {
        val key = key(); val cloud = key(); val db = open(); val repository = LocalSosRepository(db, { key }, { time })
        runBlocking { publish(repository) }
        val uploads = CopyOnWriteArrayList<SyncUploadRequest>(); val mode = AtomicInteger(503)
        val configs = MutableStateFlow<GatewayConfig?>(GatewayConfig.validated(server(cloud, mode, uploads), token, true))
        val enabled = MutableStateFlow(false)
        val sync = GatewaySync(backgroundScope, configs, enabled, HttpGatewayClient { configs.value }, repository, cloud.publicKey.encoded)
        runCurrent(); assertEquals(0, uploads.size)
        enabled.value = true
        until { sync.state.value.message.contains("will retry automatically") }
        assertEquals(LocalCloudState.QUEUED, runBlocking { repository.observeRequests().first().single().cloudState })
        mode.set(200); sync.retry()
        until { repository.observeRequests().first().single().cloudState == LocalCloudState.ACCEPTED }
        assertEquals(2, uploads.size)
        assertEquals(uploads[0].idempotencyKey, uploads[1].idempotencyKey)
        assertEquals(uploads[0].packets[0], uploads[1].packets[0])
    }

    @Test fun unsignedHttpSuccessCannotConfirmStorageAndInvalidGatewaySettingsAreRejected() = runTest {
        val key = key(); val cloud = key(); val db = open(); val repository = LocalSosRepository(db, { key }, { time })
        runBlocking { publish(repository) }
        val uploads = CopyOnWriteArrayList<SyncUploadRequest>(); val mode = AtomicInteger(400)
        val url = server(cloud, mode, uploads)
        assertThrows(IllegalArgumentException::class.java) { GatewayConfig.validated(url, token, false) }
        assertThrows(IllegalArgumentException::class.java) { GatewayConfig.validated("https://user:secret@example.com", token, false) }
        val config = GatewayConfig.validated(url, token, true)
        assertFalse(config.toString().contains(token))
        val configs = MutableStateFlow<GatewayConfig?>(config)
        val sync = GatewaySync(backgroundScope, configs, MutableStateFlow(true), HttpGatewayClient { configs.value }, repository, cloud.publicKey.encoded)
        until { sync.state.value.message.contains("could not be verified") }
        assertEquals(LocalCloudState.QUEUED, runBlocking { repository.observeRequests().first().single().cloudState })
        assertEquals(1, runBlocking { repository.cloudOutgoing().size })
        mode.set(200); sync.retry()
        until { repository.observeRequests().first().single().cloudState == LocalCloudState.ACCEPTED }
    }
}
