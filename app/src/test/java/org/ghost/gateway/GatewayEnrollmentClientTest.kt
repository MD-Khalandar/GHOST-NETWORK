package org.ghost.gateway

import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayInputStream
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.ghost.security.SigningIdentity
import org.junit.Assert.*
import org.junit.Test

class GatewayEnrollmentClientTest {
    @Test fun backgroundOrDisconnectCancellationClosesBlockedEnrollmentIoPromptly() = runBlocking {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val identity = SigningIdentity(pair.private, pair.public)
        val started = CountDownLatch(1); val release = CountDownLatch(1); val disconnected = AtomicBoolean(false)
        val socket = object : HttpURLConnection(URL("https://enrollment.invalid")) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() { disconnected.set(true); release.countDown() }
            override fun getResponseCode(): Int { started.countDown(); release.await(5, TimeUnit.SECONDS); return 200 }
            override fun getInputStream() = ByteArrayInputStream("{\"role\":\"gateway\",\"gateway_node_id\":\"${identity.nodeId.value}\"}".toByteArray())
        }
        val client = GatewayEnrollmentClient(identity.publicKey.encoded, { identity }, false, openConnection = { socket })
        val job = launch { client.checkSession(GatewayConfig("https://enrollment.invalid", "t".repeat(43))) }
        try {
            withContext(Dispatchers.IO) { assertTrue(started.await(3, TimeUnit.SECONDS)) }
            job.cancel()
            withTimeout(1000) { job.join() }
            assertTrue(disconnected.get())
        } finally { release.countDown(); job.cancelAndJoin() }
    }
}
