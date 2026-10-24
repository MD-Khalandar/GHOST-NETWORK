package org.ghost.gateway

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.ghost.data.local.LocalSosRepository
import org.ghost.domain.model.*
import org.ghost.protocol.WireCodec

data class GatewayUiState(val configured: Boolean = false, val serverUrl: String = "", val cloudKeyPinned: Boolean = false,
                          val uploading: Boolean = false, val message: String = "Optional: configure this phone as a cloud gateway.")

/** Foreground uploads share the existing durable SOS queue; no separate background service. */
@OptIn(ExperimentalCoroutinesApi::class)
class GatewaySync(scope: CoroutineScope, configs: StateFlow<GatewayConfig?>, enabled: Flow<Boolean>,
                  private val client: HttpGatewayClient, private val repository: LocalSosRepository,
                  private val cloudKey: ByteArray?) {
    private val mutableState = MutableStateFlow(GatewayUiState(cloudKeyPinned = cloudKey != null))
    val state = mutableState.asStateFlow()
    private val retry = MutableStateFlow(0)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    init {
        scope.launch {
            repository.observeRequests().map { rows -> rows.map { it.requestId to it.cloudState } }.distinctUntilChanged()
                .catch { }.collect { wake.trySend(Unit) }
        }
        scope.launch {
            combine(configs, enabled.distinctUntilChanged(), retry) { config, active, _ -> config to active }
                .collectLatest { (config, active) ->
                    client.resetReachability()
                    mutableState.value = GatewayUiState(config != null, config?.serverUrl.orEmpty(), cloudKey != null,
                        message = when {
                            config == null -> "Optional: configure this phone as a cloud gateway."
                            cloudKey == null -> "Cloud signing key is not configured for this build. Uploads are disabled."
                            !active -> "Join the mesh to upload cloud-consented SOS reports."
                            else -> "Checking saved SOS reports for upload…"
                        })
                    if (config == null || cloudKey == null || !active) return@collectLatest
                    var backoff = 5000L
                    while (currentCoroutineContext().isActive) {
                        var retryLater = false
                        try {
                            for (candidate in repository.cloudOutgoing()) {
                                val row = repository.prepareCloudUpload(candidate.eventId) ?: continue
                                mutableState.update { it.copy(uploading = true, message = "Uploading an SOS…") }
                                val result = client.upload(SyncUploadRequest("sos-${row.packetId}", listOf(WireCodec.decode(row.wire))))
                                val failure = result.exceptionOrNull()
                                if (failure != null) {
                                    val error = failure as? GatewayFailure ?: GatewayFailure("NETWORK_UNAVAILABLE", true)
                                    if (error.authentication || (!error.retryable && error.code in setOf("INVALID_RESPONSE", "HTTP_ERROR", "NOT_CONFIGURED"))) {
                                        mutableState.update { it.copy(uploading = false, message = "Gateway setup or server response needs attention. Update setup or retry.") }
                                        awaitCancellation()
                                    }
                                    if (error.retryable) { retryLater = true; break }
                                    repository.rejectCloudUpload(row.eventId, error.code)
                                    mutableState.update { it.copy(uploading = false, message = "Server rejected an SOS (${error.code}). Other reports can still upload.") }
                                    continue
                                }
                                val response = result.getOrThrow()
                                try {
                                    val item = response.results.single()
                                    require(item.eventId.value == row.eventId && item.status in setOf(CloudAckStatus.ACCEPTED, CloudAckStatus.DUPLICATE))
                                    repository.acceptCloudAck(row.eventId, response.packets.single(), cloudKey, requireNotNull(item.cloudRevision))
                                } catch (cancelled: CancellationException) { throw cancelled }
                                  catch (_: Exception) {
                                    mutableState.update { it.copy(uploading = false, message = "Cloud acknowledgement could not be verified or saved. SOS remains queued; check the server key, storage and retry.") }
                                    awaitCancellation()
                                }
                                backoff = 5000
                                mutableState.update { it.copy(uploading = false, message = "Server stored the SOS. Signed acknowledgement saved on this phone.") }
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                          catch (_: Exception) { retryLater = true }
                        mutableState.update { it.copy(uploading = false) }
                        if (retryLater) {
                            mutableState.update { it.copy(message = "Upload unavailable. SOS reports stay saved and will retry automatically.") }
                            delay(backoff); backoff = (backoff * 2).coerceAtMost(60000)
                        } else withTimeoutOrNull(30000) { wake.receive() }
                    }
                }
        }
    }
    fun retry() { retry.update { it + 1 } }
}
