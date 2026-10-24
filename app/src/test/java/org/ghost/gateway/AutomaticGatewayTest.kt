package org.ghost.gateway

import android.app.Application
import androidx.room.Room
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutomaticGatewayTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val phone = key()
    private val cloud = key()
    private val foreign = key()
    private var time = 1792860000000L
    private val servers = mutableListOf<HttpServer>()
    private val databases = mutableListOf<Pair<String, GhostDatabase>>()
    private fun key(): SigningIdentity = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        .generateKeyPair().let { SigningIdentity(it.private, it.public) }
    private class Settings : GatewaySettings {
        override val config = MutableStateFlow<GatewayConfig?>(null)
        var saved: GatewayConfig? = null
        override suspend fun load() { config.value = saved }
        override suspend fun saveAutomatic(config: GatewayConfig) { saved = config; this.config.value = config }
        override suspend fun clear() { saved = null; config.value = null }
    }
    private inner class Endpoint {
        val challenges = AtomicInteger()
        val enrollments = AtomicInteger()
        val uploads = AtomicInteger()
        val reject = AtomicReference("")
        val currentToken = AtomicReference("")
        var badChallenge = false
        var badSession = false
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { servers.add(it) }
        val url get() = "http://127.0.0.1:${server.address.port}"
        private var challenge = ByteArray(0)
        private fun signed(prefix: String, body: JsonObject, signer: SigningIdentity = cloud): String {
            val bytes = (prefix + body.toString()).toByteArray()
            return buildJsonObject { put("signed_bytes", WireCodec.base64(bytes)); put("signature", WireCodec.base64(signer.sign(bytes))) }.toString()
        }
        private fun HttpExchange.reply(status: Int, body: String) {
            val bytes = body.toByteArray()
            responseHeaders.add("Content-Type", "application/json")
            sendResponseHeaders(status, bytes.size.toLong())
            responseBody.use { it.write(bytes) }
        }
        init {
            server.createContext("/v1/gateway/challenge") { exchange ->
                challenges.incrementAndGet()
                val request = Json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject
                assertEquals(WireCodec.base64(phone.publicKey.encoded), request["public_key"]!!.jsonPrimitive.content)
                val body = buildJsonObject { put("challenge_id", UUID.randomUUID().toString()); put("node_id", phone.nodeId.value)
                    put("nonce", "n".repeat(43)); put("expires_at_ms", time + 60000) }
                challenge = ("GHOST-GATEWAY-CHALLENGE-V1\n" + body.toString()).toByteArray()
                exchange.reply(200, signed("GHOST-GATEWAY-CHALLENGE-V1\n", body, if (badChallenge) foreign else cloud))
            }
            server.createContext("/v1/gateway/enroll") { exchange ->
                val request = Json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject
                assertArrayEquals(challenge, WireCodec.unbase64(request["challenge_bytes"]!!.jsonPrimitive.content, 1024))
                assertTrue(SigningIdentity.verify(phone.publicKey.encoded, challenge, WireCodec.unbase64(request["signature"]!!.jsonPrimitive.content, 72)))
                val count = enrollments.incrementAndGet()
                currentToken.set("t".repeat(40) + count.toString().padStart(3, '0'))
                exchange.reply(200, signed("GHOST-GATEWAY-SESSION-V1\n", buildJsonObject {
                    put("node_id", phone.nodeId.value); put("token", currentToken.get()); put("expires_at_ms", time + 86400000)
                }, if (badSession) foreign else cloud))
            }
            server.createContext("/v1/auth/session") { exchange ->
                val token = exchange.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")
                if (token != currentToken.get() || token == reject.get()) exchange.reply(401, """{"code":"UNAUTHENTICATED"}""")
                else exchange.reply(200, buildJsonObject { put("role", "gateway"); put("gateway_node_id", phone.nodeId.value) }.toString())
            }
            server.createContext("/v1/sync/upload") { exchange ->
                assertEquals("Bearer ${currentToken.get()}", exchange.requestHeaders.getFirst("Authorization"))
                val request = Json.decodeFromString<SyncUploadRequest>(exchange.requestBody.readBytes().decodeToString())
                uploads.incrementAndGet()
                val envelope = ImmutableEnvelopeV1.parseFrom(WireCodec.unbase64(request.packets.single().immutableEnvelopeBytesBase64, 24576))
                val ack = SignedPackets.create(cloud, 7, CloudAckV1.newBuilder().setOriginEventId(envelope.eventId)
                    .setStatusValue(1).setCloudRevision(1).setReceivedAtMs(time).build().toByteString(), time, destination = phone.nodeId.value)
                exchange.reply(200, Json.encodeToString(SyncUploadResponse(listOf(SyncUploadItemResult(EventId(envelope.eventId), CloudAckStatus.ACCEPTED, 1)), listOf(ack))))
            }
            server.start()
        }
    }
    private fun TestScope.until(condition: () -> Boolean) {
        repeat(1000) { runCurrent(); if (condition()) return; Thread.sleep(5) }
        fail("Automatic gateway did not reach the expected state")
    }
    @After fun close() {
        servers.forEach { it.stop(0) }
        databases.forEach { (name, db) -> db.close(); context.deleteDatabase(name) }
    }

    @Test fun oneConnectEnrollsAndUploadsOnlyConsentedReportsWhileForegroundAndOnWifi() = runTest {
        val endpoint = Endpoint(); val settings = Settings()
        val enabled = MutableStateFlow(false); val wifi = MutableStateFlow(false)
        val name = "automatic-${UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(context, GhostDatabase::class.java, name).build(); databases.add(name to db)
        val repo = LocalSosRepository(db, { phone }, { time })
        runBlocking {
            for (cloudConsent in listOf(false, true)) repo.publish(SosCreatePayload(RequestId(UUID.randomUUID().toString()),
                SosCategory.WATER, "Need water", consent = Consent(true, cloudConsent))).getOrThrow()
            repo.publish(SosCreatePayload(RequestId(UUID.randomUUID().toString()), SosCategory.WATER,
                "Private request", consent = Consent(false, true))).getOrThrow()
        }
        val http = HttpGatewayClient { settings.config.value }
        val auto = AutomaticGateway(backgroundScope, settings, endpoint.url,
            GatewayEnrollmentClient(cloud.publicKey.encoded, { phone }, true, { time }), enabled, wifi, http::recordReachability, { time })
        GatewaySync(backgroundScope, settings.config, combine(enabled, wifi) { a,b -> a && b }, http, repo, cloud.publicKey.encoded,
            onAuthenticationFailure = { auto.retry(clearSession = true) })
        runCurrent(); assertEquals(0, endpoint.challenges.get())
        enabled.value = true; runCurrent(); assertEquals(0, endpoint.challenges.get())
        wifi.value = true
        until { endpoint.uploads.get() == 1 && auto.state.value.online }
        until { runBlocking { repo.observeRequests().first().any { it.cloudState == LocalCloudState.ACCEPTED } } }
        assertEquals(1, endpoint.enrollments.get())
        assertEquals(1, runBlocking { repo.observeRequests().first().count { it.cloudState == LocalCloudState.ACCEPTED } })
        assertNotNull(settings.saved?.expiresAtMs)
        enabled.value = false; runCurrent(); assertFalse(auto.state.value.online)
        val checks = endpoint.challenges.get()
        advanceTimeBy(60001); runCurrent(); assertEquals(checks, endpoint.challenges.get())
        assertEquals(1, endpoint.uploads.get())
    }

    @Test fun storedSessionsAreReusedRenewedBeforeExpiryAndRecoveredAfterUnauthorized() = runTest {
        val endpoint = Endpoint(); val settings = Settings()
        val enabled = MutableStateFlow(true); val wifi = MutableStateFlow(true)
        fun connect(scope: CoroutineScope) = AutomaticGateway(scope, settings, endpoint.url,
            GatewayEnrollmentClient(cloud.publicKey.encoded, { phone }, true, { time }), enabled, wifi, {}, { time })
        val firstScope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        val first = connect(firstScope)
        until { first.state.value.online }; assertEquals(1, endpoint.enrollments.get())
        firstScope.cancel(); runCurrent()
        val restored = connect(backgroundScope)
        until { restored.state.value.online }; assertEquals(1, endpoint.enrollments.get())
        time += 86400000
        advanceTimeBy(30001)
        until { endpoint.enrollments.get() == 2 && restored.state.value.online }
        endpoint.reject.set(endpoint.currentToken.get())
        restored.retry()
        until { settings.config.value == null }
        advanceTimeBy(5001)
        until { endpoint.enrollments.get() == 3 && restored.state.value.online }
        wifi.value = false; runCurrent(); assertFalse(restored.state.value.online)
        assertTrue(restored.state.value.message.contains("Wi-Fi"))
    }

    @Test fun serverIdentityIsVerifiedBeforeSigningAChallengeAndBeforeAcceptingCredentials() = runBlocking {
        val endpoint = Endpoint()
        val client = GatewayEnrollmentClient(cloud.publicKey.encoded, { phone }, true, { time })
        endpoint.badChallenge = true
        try { client.enroll(endpoint.url); fail("Untrusted challenge accepted") } catch (error: GatewayFailure) { assertEquals("UNTRUSTED_SERVER", error.code) }
        assertEquals(0, endpoint.enrollments.get())
        endpoint.badChallenge = false; endpoint.badSession = true
        try { client.enroll(endpoint.url); fail("Untrusted session accepted") } catch (error: GatewayFailure) { assertEquals("UNTRUSTED_SERVER", error.code) }
        assertEquals(1, endpoint.enrollments.get())
    }
}
