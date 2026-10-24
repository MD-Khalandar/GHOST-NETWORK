package org.ghost.gateway

import java.net.HttpURLConnection
import java.net.URI
import java.io.InputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.ghost.domain.model.*
import org.ghost.gateway.api.*

class GatewayFailure(val code: String, val retryable: Boolean, val authentication: Boolean = false) : Exception(code)

/** Bounded HTTPS upload. Redirects never receive the gateway bearer token. */
class HttpGatewayClient(private val config: () -> GatewayConfig?) : GatewayClient {
    override val reachability = MutableStateFlow(GatewayReachability.UNKNOWN)
    val reachabilityCheckedAt = MutableStateFlow(0L)
    fun resetReachability() { reachability.value = GatewayReachability.UNKNOWN; reachabilityCheckedAt.value = 0 }
    private fun recordReachability(status: GatewayReachability) {
        reachability.value = status
        reachabilityCheckedAt.value = System.currentTimeMillis()
    }
    private val json = Json { ignoreUnknownKeys = true }
    override suspend fun upload(request: SyncUploadRequest): Result<SyncUploadResponse> = coroutineScope {
        val settings = config() ?: return@coroutineScope Result.failure(GatewayFailure("NOT_CONFIGURED", false))
        var connection: HttpURLConnection? = null
        try {
            require(request.packets.size in 1..50)
            currentCoroutineContext().ensureActive()
            val socket = URI("${settings.serverUrl}/v1/sync/upload").toURL().openConnection() as HttpURLConnection
            connection = socket
            withTimeout(20000) {
                val transfer = async(Dispatchers.IO) { post(socket, settings, request) }
                // Closing also unblocks IO when the app backgrounds or setup changes.
                try { Result.success(transfer.await()) } finally { socket.disconnect() }
            }
        } catch (_: TimeoutCancellationException) {
            recordReachability(GatewayReachability.OFFLINE)
            Result.failure(GatewayFailure("NETWORK_UNAVAILABLE", true))
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (failure: GatewayFailure) { Result.failure(failure) }
          catch (_: Exception) {
            recordReachability(GatewayReachability.OFFLINE)
            Result.failure(GatewayFailure("NETWORK_UNAVAILABLE", true))
        } finally { connection?.disconnect() }
    }
    override suspend fun download(cursor: String?, limit: Int): Result<SyncDownloadResponse> = Result.failure(UnsupportedOperationException())
    override suspend fun acknowledgeDownload(request: SyncDownloadAckRequest): Result<Unit> = Result.failure(UnsupportedOperationException())

    private suspend fun post(socket: HttpURLConnection, settings: GatewayConfig, request: SyncUploadRequest): SyncUploadResponse {
        currentCoroutineContext().ensureActive()
        socket.apply {
            requestMethod = "POST"; instanceFollowRedirects = false
            connectTimeout = 10000; readTimeout = 10000; doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${settings.token}")
        }
        val bytes = json.encodeToString(request).encodeToByteArray()
        require(bytes.size <= 2400000)
        socket.setFixedLengthStreamingMode(bytes.size)
        socket.outputStream.use { it.write(bytes) }
        val status = socket.responseCode
        currentCoroutineContext().ensureActive()
        recordReachability(if (status == 401 || status == 403) GatewayReachability.AUTHENTICATION_FAILED else GatewayReachability.ONLINE)
        if (status !in 200..299) {
            val code = socket.errorStream?.use { stream ->
                val body = readBounded(stream, 4096).decodeToString()
                runCatching { json.parseToJsonElement(body).jsonObject["code"]?.jsonPrimitive?.content }
                    .getOrNull()?.takeIf { it.matches(Regex("[A-Z_]{1,64}")) }
            } ?: "HTTP_ERROR"
            throw GatewayFailure(code, status == 408 || status == 429 || status >= 500, status == 401 || status == 403)
        }
        val response = socket.inputStream.use { readBounded(it, 1024 * 1024) }
        if (response.size > 1024 * 1024) throw GatewayFailure("INVALID_RESPONSE", false)
        val parsed = try { json.decodeFromString<SyncUploadResponse>(response.decodeToString()) }
            catch (_: Exception) { throw GatewayFailure("INVALID_RESPONSE", false) }
        if (parsed.results.size != request.packets.size || parsed.packets.size != request.packets.size)
            throw GatewayFailure("INVALID_RESPONSE", false)
        return parsed
    }

    private fun readBounded(stream: InputStream, maximum: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() <= maximum) {
            val count = stream.read(buffer, 0, minOf(buffer.size, maximum + 1 - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
