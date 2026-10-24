package org.ghost.gateway

import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.ghost.protocol.WireCodec
import org.ghost.security.SigningIdentity

@Serializable
private data class SignedEnrollment(val signed_bytes: String, val signature: String)

/** Both handshake replies are checked against the bundled server key, including on debug HTTP. */
class GatewayEnrollmentClient(private val cloudKey: ByteArray, private val identity: () -> SigningIdentity,
                              private val allowHttp: Boolean, private val now: () -> Long = System::currentTimeMillis,
                              private val openConnection: (java.net.URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun enroll(serverUrl: String): GatewayConfig {
        val url = GatewayConfig.serverAddress(serverUrl, allowHttp)
        val key = withContext(Dispatchers.IO) { identity() }
        val challenge = request(url, "/v1/gateway/challenge", buildJsonObject {
            put("public_key", WireCodec.base64(key.publicKey.encoded))
        }.toString())
        val reply = json.decodeFromString<SignedEnrollment>(challenge)
        val data = verified(reply, "GHOST-GATEWAY-CHALLENGE-V1\n")
        requireServer(data["node_id"]?.jsonPrimitive?.content == key.nodeId.value)
        val expiry = data["expires_at_ms"]?.jsonPrimitive?.longOrNull ?: 0
        requireServer(expiry in (now() - 300000)..(now() + 300000))
        requireServer(data["nonce"]?.jsonPrimitive?.content?.matches(Regex("[A-Za-z0-9_-]{43}")) == true)
        val signature = withContext(Dispatchers.IO) {
            WireCodec.base64(key.sign(WireCodec.unbase64(reply.signed_bytes, 1024)))
        }
        val session = request(url, "/v1/gateway/enroll", buildJsonObject {
            put("challenge_bytes", reply.signed_bytes); put("signature", signature)
        }.toString())
        val credential = verified(json.decodeFromString<SignedEnrollment>(session), "GHOST-GATEWAY-SESSION-V1\n")
        requireServer(credential["node_id"]?.jsonPrimitive?.content == key.nodeId.value)
        val expires = credential["expires_at_ms"]?.jsonPrimitive?.longOrNull ?: 0
        requireServer(expires > now() + 60000 && expires <= now() + 25 * 3600000L)
        val token = credential["token"]?.jsonPrimitive?.content ?: throw GatewayFailure("UNTRUSTED_SERVER", false)
        return GatewayConfig.validated(url, token, allowHttp).copy(expiresAtMs = expires)
    }

    suspend fun checkSession(config: GatewayConfig) {
        val response = json.parseToJsonElement(request(config.serverUrl, "/v1/auth/session", token = config.token)).jsonObject
        val node = withContext(Dispatchers.IO) { identity().nodeId.value }
        requireServer(response["role"]?.jsonPrimitive?.content == "gateway" && response["gateway_node_id"]?.jsonPrimitive?.content == node)
    }

    private fun requireServer(valid: Boolean) { if (!valid) throw GatewayFailure("UNTRUSTED_SERVER", false) }
    private suspend fun verified(reply: SignedEnrollment, prefix: String): JsonObject = withContext(Dispatchers.IO) {
        try {
            val bytes = WireCodec.unbase64(reply.signed_bytes, 1024)
            requireServer(SigningIdentity.verify(cloudKey, bytes, WireCodec.unbase64(reply.signature, 72)))
            val text = bytes.toString(Charsets.UTF_8)
            requireServer(text.startsWith(prefix))
            json.parseToJsonElement(text.removePrefix(prefix)).jsonObject
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { throw GatewayFailure("UNTRUSTED_SERVER", false) }
    }

    private suspend fun request(url: String, path: String, body: String? = null, token: String? = null): String = coroutineScope {
        val socket = openConnection(URI("$url$path").toURL())
        try {
            withTimeout(20000) {
                val transfer = async(Dispatchers.IO) {
                    socket.apply {
                        requestMethod = if (body == null) "GET" else "POST"
                        instanceFollowRedirects = false; connectTimeout = 10000; readTimeout = 10000
                        token?.let { setRequestProperty("Authorization", "Bearer $it") }
                        if (body != null) {
                            doOutput = true; setRequestProperty("Content-Type", "application/json")
                            val bytes = body.toByteArray(); setFixedLengthStreamingMode(bytes.size)
                            outputStream.use { it.write(bytes) }
                        }
                    }
                    val status = socket.responseCode
                    if (status !in 200..299) {
                        val error = socket.errorStream?.use { stream ->
                            val raw = stream.readNBytesCompat(4096)
                            runCatching { json.parseToJsonElement(raw).jsonObject["code"]?.jsonPrimitive?.content }.getOrNull()
                        }?.takeIf { it.matches(Regex("[A-Z_]{1,64}")) } ?: "HTTP_ERROR"
                        throw GatewayFailure(error, status >= 500 || status == 429 || status == 408, status == 401 || status == 403)
                    }
                    socket.inputStream.use { it.readNBytesCompat(8192) }
                }
                // Close immediately when await is cancelled, before waiting for blocked IO.
                try { transfer.await() } finally { socket.disconnect() }
            }
        } finally { socket.disconnect() }
    }
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): String {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(1024)
    while (out.size() <= limit) {
        val n = read(buffer, 0, minOf(buffer.size, limit + 1 - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    if (out.size() > limit) throw GatewayFailure("INVALID_RESPONSE", false)
    return out.toString("UTF-8")
}
