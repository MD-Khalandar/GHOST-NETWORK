package org.ghost.simulation

import java.util.ArrayDeque
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ghost.domain.model.*
import org.ghost.gateway.api.GatewayClient
import org.ghost.gateway.api.GatewayReachability

data class FakeCloudCall<T>(val request: T, val attemptedAtMs: Long)
data class FakeDownloadQuery(val cursor: String?, val limit: Int)

/** Offline by default. Every first successful operation must be explicitly scripted.
 * This is a client fixture, not a server policy, signature verifier, or origin-event deduplicator.
 */
class FakeCloudClient(private val clock: FakeClock) : GatewayClient {
    private val mutex = Mutex()
    private val mutableReachability = MutableStateFlow(GatewayReachability.OFFLINE)
    override val reachability = mutableReachability.asStateFlow()
    private val uploads = Script<SyncUploadRequest, SyncUploadResponse>()
    private val downloads = Script<FakeDownloadQuery, SyncDownloadResponse>()
    private val acknowledgements = Script<SyncDownloadAckRequest, Unit>()
    private val acceptedUploads = mutableMapOf<String, Pair<SyncUploadRequest, SyncUploadResponse>>()
    private val downloaded = mutableMapOf<FakeDownloadQuery, SyncDownloadResponse>()
    private val acknowledged = mutableSetOf<SyncDownloadAckRequest>()
    private val mutableUploadCalls = MutableStateFlow<List<FakeCloudCall<SyncUploadRequest>>>(emptyList())
    private val mutableDownloadCalls = MutableStateFlow<List<FakeCloudCall<FakeDownloadQuery>>>(emptyList())
    private val mutableAckCalls = MutableStateFlow<List<FakeCloudCall<SyncDownloadAckRequest>>>(emptyList())
    val uploadCalls = mutableUploadCalls.asStateFlow()
    val downloadCalls = mutableDownloadCalls.asStateFlow()
    val acknowledgementCalls = mutableAckCalls.asStateFlow()

    suspend fun setReachability(value: GatewayReachability) = mutex.withLock {
        mutableReachability.value = value
    }

    suspend fun enqueueUploadResult(request: SyncUploadRequest, result: Result<SyncUploadResponse>) = mutex.withLock {
        uploads.add(request.copy(packets = request.packets.snapshot(), receipts = request.receipts.snapshot()),
            result.map { it.copy(results = it.results.snapshot()) })
    }

    suspend fun enqueueDownloadResult(cursor: String?, limit: Int, result: Result<SyncDownloadResponse>) = mutex.withLock {
        require(limit in 1..100) { "CP01 download limit must be 1..100" }
        result.getOrNull()?.let { require(it.packets.size <= limit) { "Fixture page exceeds its requested limit" } }
        downloads.add(FakeDownloadQuery(cursor, limit), result.map { it.copy(packets = it.packets.snapshot()) })
    }

    suspend fun enqueueAcknowledgementResult(request: SyncDownloadAckRequest, result: Result<Unit>) = mutex.withLock {
        acknowledgements.add(request.copy(packetIds = request.packetIds.snapshot()), result)
    }

    override suspend fun upload(request: SyncUploadRequest): Result<SyncUploadResponse> = mutex.withLock {
        val copy = request.copy(packets = request.packets.snapshot(), receipts = request.receipts.snapshot())
        mutableUploadCalls.value = (uploadCalls.value + FakeCloudCall(copy, clock.nowMs)).snapshot()
        unavailable()?.let { return@withLock Result.failure(it) }
        acceptedUploads[copy.idempotencyKey]?.let { (previous, response) ->
            return@withLock if (previous == copy) Result.success(response)
            else Result.failure(IllegalArgumentException("Simulated idempotency key reused with different content"))
        }
        uploads.take(copy).also { result ->
            result.getOrNull()?.let { acceptedUploads[copy.idempotencyKey] = copy to it }
        }
    }

    override suspend fun download(cursor: String?, limit: Int): Result<SyncDownloadResponse> = mutex.withLock {
        val query = FakeDownloadQuery(cursor, limit)
        mutableDownloadCalls.value = (downloadCalls.value + FakeCloudCall(query, clock.nowMs)).snapshot()
        if (limit !in 1..100) return@withLock Result.failure(IllegalArgumentException("CP01 download limit must be 1..100"))
        unavailable()?.let { return@withLock Result.failure(it) }
        downloaded[query]?.let { return@withLock Result.success(it) }
        downloads.take(query).also { result -> result.getOrNull()?.let { downloaded[query] = it } }
    }

    override suspend fun acknowledgeDownload(request: SyncDownloadAckRequest): Result<Unit> = mutex.withLock {
        val copy = request.copy(packetIds = request.packetIds.snapshot())
        mutableAckCalls.value = (acknowledgementCalls.value + FakeCloudCall(copy, clock.nowMs)).snapshot()
        unavailable()?.let { return@withLock Result.failure(it) }
        if (copy in acknowledged) return@withLock Result.success(Unit)
        acknowledgements.take(copy).also { if (it.isSuccess) acknowledged.add(copy) }
    }

    private fun unavailable(): Throwable? = when (reachability.value) {
        GatewayReachability.ONLINE -> null
        else -> IllegalStateException("Simulated cloud unavailable: ${reachability.value}")
    }

    private class Script<Q, R> {
        private val steps = ArrayDeque<Pair<Q, Result<R>>>()

        fun add(query: Q, result: Result<R>) {
            steps.addLast(query to result)
        }

        fun take(query: Q): Result<R> {
            val step = steps.peekFirst()
                ?: return Result.failure(IllegalStateException("No simulated cloud result scripted"))
            if (step.first != query) {
                return Result.failure(IllegalArgumentException("Request does not match the next simulated cloud step"))
            }
            val result = steps.removeFirst().second
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            return result
        }
    }
}
