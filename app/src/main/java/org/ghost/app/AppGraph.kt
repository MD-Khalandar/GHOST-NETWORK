package org.ghost.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.Channel
import org.ghost.data.local.GhostDatabase
import org.ghost.data.local.LocalSosRepository
import org.ghost.domain.model.*
import org.ghost.mesh.DirectExchange
import org.ghost.mesh.Connectivity
import org.ghost.mesh.NodeCapabilities
import org.ghost.mesh.observeInternet
import org.ghost.gateway.api.GatewayReachability
import org.ghost.security.AndroidSigningIdentity
import org.ghost.transport.nearby.NearbyAdapter
import org.ghost.BuildConfig
import org.ghost.gateway.*
import org.ghost.protocol.WireCodec
import org.ghost.security.SigningIdentity

class AppGraph(application: Application) : AndroidViewModel(application) {
    val location = LastKnownLocationProvider(application)
    private val db = Room.databaseBuilder(application, GhostDatabase::class.java, "ghost.db")
        .addMigrations(GhostDatabase.MIGRATION_1_2, GhostDatabase.MIGRATION_2_3, GhostDatabase.MIGRATION_3_4).build()
    private val identity by lazy { AndroidSigningIdentity().loadOrCreate() }
    val nearby = NearbyAdapter(application, viewModelScope)
    val repository = LocalSosRepository(db, { identity })
    private val gatewaySettings = GatewayConfigStore(application, BuildConfig.DEBUG)
    private val cloudKey = runCatching {
        BuildConfig.CLOUD_PUBLIC_KEY.takeIf { it.isNotEmpty() }?.let { WireCodec.unbase64(it, 91).also(SigningIdentity::decodePublicKey) }
    }.getOrNull()
    private val gatewayClient = HttpGatewayClient { gatewaySettings.config.value }
    private val gateway = GatewaySync(viewModelScope, gatewaySettings.config, nearby.state.map { it.active }, gatewayClient, repository, cloudKey)
    private val internet = observeInternet(application).stateIn(viewModelScope, SharingStarted.Eagerly, Connectivity.UNKNOWN)
    private val exchange = DirectExchange(viewModelScope, nearby, repository, { identity }, cloudKey = cloudKey, capabilities = {
        val configured = gateway.state.value.configured && cloudKey != null
        val fresh = System.currentTimeMillis() - gatewayClient.reachabilityCheckedAt.value in 0..60000
        NodeCapabilities(internet.value, configured, if (!configured || !fresh) Connectivity.UNKNOWN else when (gatewayClient.reachability.value) {
            GatewayReachability.ONLINE -> Connectivity.AVAILABLE
            GatewayReachability.OFFLINE, GatewayReachability.AUTHENTICATION_FAILED -> Connectivity.UNAVAILABLE
            GatewayReachability.UNKNOWN -> Connectivity.UNKNOWN
        })
    })
    val dependencies = GhostAppDependencies(nearby, gatewayClient, repository, UnavailableOffers, UploadCoordinationState(repository))
    private val operation = MutableStateFlow(LocalUiState())
    private data class DraftWrite(val draft: SosDraft, val complete: (() -> Unit)? = null)
    private val draftWrites = Channel<DraftWrite>(Channel.CONFLATED)
    val local: StateFlow<LocalUiState> = combine(repository.observeDraft(), operation, nearby.state, exchange.identities, exchange.message) {
        draft, state, network, identities, message -> state.copy(draft = draft, network = network, peerIdentities = identities, exchangeStatus = message)
    }.combine(gateway.state) { state, cloud -> state.copy(gateway = cloud) }
        .combine(exchange.topology) { state, topology -> state.copy(topology = topology) }
        .combine(internet) { state, status -> state.copy(topology = state.topology.copy(
            localCapabilities = state.topology.localCapabilities.copy(internet = status))) }
        .catch { emit(LocalUiState(error = "Local storage could not be opened.")) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, LocalUiState())

    init {
        viewModelScope.launch {
            try { cloudKey?.let { repository.restoreCloudReturns(it) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { operation.update { it.copy(error = "Saved server acknowledgements could not be queued. Check storage and retry.") } }
        }
        viewModelScope.launch {
            try { gatewaySettings.load() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { operation.update { it.copy(error = "Gateway settings could not be opened. Reconfigure this phone's gateway.") } }
        }
        viewModelScope.launch {
            for (write in draftWrites) {
                try {
                    repository.saveDraft(write.draft)
                    if (write.complete != null) { operation.value = LocalUiState(); write.complete.invoke() }
                } catch (cancelled: CancellationException) { throw cancelled }
                  catch (_: Exception) { operation.value = LocalUiState(error = "Draft could not be saved. Try again.") }
            }
        }
        viewModelScope.launch {
            try { repository.initializeDraft() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { operation.value = LocalUiState(error = "Could not load the SOS draft.") }
        }
    }
    fun saveDraft(draft: SosDraft, saved: () -> Unit) {
        if (operation.value.saving) return
        operation.value = LocalUiState(saving = true)
        draftWrites.trySend(DraftWrite(draft, saved))
    }
    fun publish(draft: SosDraft, saved: () -> Unit) {
        if (operation.value.saving) return
        operation.value = LocalUiState(saving = true)
        viewModelScope.launch {
            try {
                val payload = SosCreatePayload(RequestId(draft.requestId), draft.category, draft.description.trim(),
                    draft.people.takeIf { it.isNotBlank() }?.toIntOrNull(), draft.area.trim().takeIf { it.isNotEmpty() },
                    consent = Consent(draft.publicSharing, draft.cloudConsent),
                    location = if (draft.locationSharing) location.read() else null)
                require(draft.people.isBlank() || payload.peopleCount != null) { "Enter a valid people count." }
                repository.publish(payload, draft.locationSharing).getOrThrow()
                operation.value = LocalUiState(notice = if (draft.publicSharing) "SOS saved. Queued for the mesh." else "SOS saved on this phone only.")
                saved(); exchange.flush()
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (error: Exception) { operation.value = LocalUiState(error = if (error is IllegalArgumentException) error.message else "SOS could not be saved. Check storage and the signing key, then retry.") }
        }
    }
    private var meshEnabled = false
    private var meshStart: Job? = null
    fun startNearby() { meshEnabled = true; resumeNearby() }
    fun resumeNearby() {
        if (!meshEnabled || meshStart?.isActive == true || nearby.state.value.active) return
        meshStart = viewModelScope.launch {
            try {
                val name = withContext(Dispatchers.IO) { "GHOST ${identity.nodeId.value.take(8)}" }
                nearby.start(name)
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { operation.value = LocalUiState(error = "Nearby could not start. Check the signing key, permissions and Google Play services.") }
        }
    }
    fun pauseNearby() { meshStart?.cancel(); meshStart = null; nearby.close() }
    fun stopNearby() { meshEnabled = false; pauseNearby() }
    private fun saveGateway(url: String, token: String, saved: () -> Unit) {
        if (operation.value.saving) return
        operation.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                gatewaySettings.save(url, token)
                repository.retryCloudUploads(); gateway.retry()
                operation.update { it.copy(saving = false, error = null) }
                saved()
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (error: Exception) { operation.update { it.copy(saving = false, error = if (error is IllegalArgumentException) error.message else "Gateway settings could not be saved. Check Android Keystore and retry.") } }
        }
    }
    private fun clearGateway() {
        viewModelScope.launch {
            try { gatewaySettings.clear(); operation.update { it.copy(error = null) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { operation.update { it.copy(error = "Gateway settings could not be removed. Try again.") } }
        }
    }
    private fun retrySends() {
        exchange.retry()
        viewModelScope.launch {
            try { repository.retryCloudUploads(); cloudKey?.let { repository.restoreCloudReturns(it) }; gateway.retry(); exchange.flush() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { operation.update { it.copy(error = "Cloud queue could not be reopened. Try again.") } }
        }
    }
    fun actions(start: () -> Unit, requestLocation: () -> Unit = {}) = LocalActions(
        { draft -> if (!operation.value.saving) draftWrites.trySend(DraftWrite(draft)) },
        ::saveDraft, ::publish, start, ::stopNearby, ::retrySends, ::saveGateway, ::clearGateway, requestLocation)

    override fun onCleared() {
        // SDK stop calls are immediate; the app also stops links when its activity backgrounds.
        nearby.close()
        db.close()
        super.onCleared()
    }
}
