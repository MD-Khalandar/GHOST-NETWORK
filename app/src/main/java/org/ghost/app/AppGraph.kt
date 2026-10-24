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
import org.ghost.mesh.observeServerNetwork
import org.ghost.mesh.openServerConnection
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
        .addMigrations(GhostDatabase.MIGRATION_1_2, GhostDatabase.MIGRATION_2_3, GhostDatabase.MIGRATION_3_4, GhostDatabase.MIGRATION_4_5).build()
    private val identity by lazy { AndroidSigningIdentity().loadOrCreate() }
    val nearby = NearbyAdapter(application, viewModelScope)
    val repository = LocalSosRepository(db, { identity })
    private val gatewaySettings = GatewayConfigStore(application, BuildConfig.DEBUG)
    private val cloudKey = runCatching {
        BuildConfig.CLOUD_PUBLIC_KEY.takeIf { it.isNotEmpty() }?.let { WireCodec.unbase64(it, 91).also(SigningIdentity::decodePublicKey) }
    }.getOrNull()
    private val gatewayClient = HttpGatewayClient(openConnection = { openServerConnection(application, it) }) { gatewaySettings.config.value }
    private val connectionActive = MutableStateFlow(false)
    val connectionRequested = connectionActive.asStateFlow()
    private val serverNetwork = observeServerNetwork(application).stateIn(viewModelScope, SharingStarted.Eagerly, false)
    private val automaticGateway = AutomaticGateway(viewModelScope, gatewaySettings, BuildConfig.SERVER_URL,
        cloudKey?.let { GatewayEnrollmentClient(it, { identity }, BuildConfig.DEBUG,
            openConnection = { url -> openServerConnection(application, url) }) }, connectionActive, serverNetwork,
        gatewayClient::recordReachability)
    private val gateway = GatewaySync(viewModelScope, gatewaySettings.config,
        combine(connectionActive, serverNetwork) { active, connected -> active && connected }, gatewayClient, repository, cloudKey,
        onAuthenticationFailure = { automaticGateway.retry(clearSession = true) })
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
    val dependencies = GhostAppDependencies(nearby, gatewayClient, repository, repository.resources, UploadCoordinationState(repository))
    private val operation = MutableStateFlow(LocalUiState())
    private data class DraftWrite(val draft: SosDraft, val complete: (() -> Unit)? = null)
    private val draftWrites = Channel<DraftWrite>(Channel.CONFLATED)
    val local: StateFlow<LocalUiState> = combine(repository.observeDraft(), operation, nearby.state, exchange.identities, exchange.message) {
        draft, state, network, identities, message -> state.copy(draft = draft, network = network, peerIdentities = identities, exchangeStatus = message)
    }.combine(gateway.state) { state, cloud -> state.copy(gateway = cloud) }
        .combine(automaticGateway.state) { state, connection -> state.copy(gateway = state.gateway.copy(
            automatic = connection.available, active = connection.active, online = connection.online,
            serverUrl = BuildConfig.SERVER_URL.ifBlank { state.gateway.serverUrl },
            message = if (connection.available && (!connection.online || !state.gateway.uploading &&
                state.gateway.message.startsWith("Checking saved"))) connection.message else state.gateway.message)) }
        .combine(repository.resources.observeSummaries()) { state, resources -> state.copy(resources = resources, resourcesLoaded = true) }
        .combine(repository.assistance.observe()) { state, assistance -> state.copy(assistance=assistance) }
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
                    consent = Consent(true, true),
                    location = if (draft.locationSharing) location.current() else null)
                require(draft.people.isBlank() || payload.peopleCount != null) { "Enter a valid people count." }
                repository.publish(payload, draft.locationSharing).getOrThrow()
                operation.value = LocalUiState(notice = "SOS saved. Queued for nearby sharing and online coordination.")
                saved(); exchange.flush()
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (error: Exception) { operation.value = LocalUiState(error = if (error is IllegalArgumentException) error.message else "SOS could not be saved. Check storage and the signing key, then retry.") }
        }
    }
    private var meshStart: Job? = null
    fun startNearby() {
        try { GhostConnectionService.connect(getApplication()) }
        catch (_: Exception) { connectionFailed() }
    }
    internal fun beginConnection() {
        if (connectionActive.value) return
        connectionActive.value = true
        meshStart = viewModelScope.launch {
            try {
                val name = withContext(Dispatchers.IO) { "GHOST ${identity.nodeId.value.take(8)}" }
                nearby.start(name)
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { operation.value = LocalUiState(error = "Nearby could not start. Check the signing key, permissions and Google Play services.") }
        }
    }
    internal fun endConnection() { connectionActive.value = false; meshStart?.cancel(); meshStart = null; nearby.close() }
    internal fun connectionFailed() {
        endConnection()
        operation.update { it.copy(error = "GHOST could not keep the connection active. Open the app and tap Connect again.") }
    }
    fun stopNearby() { endConnection(); GhostConnectionService.disconnect(getApplication()) }
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
        automaticGateway.retry()
        exchange.retry()
        viewModelScope.launch {
            try { repository.retryCloudUploads(); cloudKey?.let { repository.restoreCloudReturns(it) }; gateway.retry(); exchange.flush() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { operation.update { it.copy(error = "Cloud queue could not be reopened. Try again.") } }
        }
    }
    private fun publishResource(draft: ResourceDraft, saved: () -> Unit, withdraw: Boolean = false) {
        if (operation.value.saving) return
        operation.update { it.copy(saving = true, error = null, notice = null) }
        viewModelScope.launch {
            try {
                val publication = draft.withRequiredSharing()
                (if (withdraw) repository.resources.updateOffer(draft, withdraw = true) else repository.resources.publish(publication)).getOrThrow()
                operation.update { it.copy(saving = false, notice = if (withdraw)
                    (if (draft.publicSharing) "Withdrawn on this phone. Waiting to share the withdrawal." else "Offer withdrawn on this phone.")
                    else if (publication.publicSharing && publication.cloudConsent) "Help saved. Waiting to share with nearby phones and online coordination."
                    else if (publication.publicSharing) "Offer updated. Its original nearby-only sharing is unchanged."
                    else "Offer updated on this phone only.") }
                saved(); exchange.flush()
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (error: Exception) { operation.update { it.copy(saving = false, error = if (error is IllegalArgumentException) error.message else "Could not save help. Check storage and the signing key.") } }
        }
    }
    fun actions(start: () -> Unit, requestLocation: () -> Unit = {}) = LocalActions(
        { draft -> if (!operation.value.saving) draftWrites.trySend(DraftWrite(draft)) },
        ::saveDraft, ::publish, start, ::stopNearby, ::retrySends, ::saveGateway, ::clearGateway,
        publishResource = { draft, saved -> publishResource(draft, saved) },
        withdrawResource = { draft, saved -> publishResource(draft, saved, withdraw = true) },
        decideAssistance = {id,version,decision->
            if(!operation.value.saving)viewModelScope.launch {
                operation.update{it.copy(saving=true,error=null)}
                try{repository.assistance.decide(id,version,decision,requireNotNull(cloudKey))
                    operation.update{it.copy(saving=false,notice="Decision saved. Waiting for server confirmation.")};exchange.flush()}
                catch(cancelled:CancellationException){throw cancelled}
                catch(error:Exception){operation.update{it.copy(saving=false,error=if(error is IllegalArgumentException)error.message else "Could not save the decision. Refresh and retry.")}}
            }
        },
        requestLocation = requestLocation, closeSos = ::closeSos)

    private fun closeSos(requestId: String, state: SosState) {
        if (operation.value.saving) return
        operation.update { it.copy(saving = true, error = null, notice = null) }
        viewModelScope.launch {
            try {
                val result = repository.closures.close(requestId, state).getOrThrow()
                operation.update { it.copy(saving = false, notice = if (result.queued)
                    "Closure saved on this phone. Waiting to share with nearby phones and any permitted online gateway."
                    else "Request closed on this phone only.") }
                exchange.flush()
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (error: Exception) { operation.update { it.copy(saving = false, error = if (error is IllegalArgumentException) error.message else "Could not close this SOS. Check storage and retry.") } }
        }
    }

    override fun onCleared() {
        endConnection()
        db.close()
        super.onCleared()
    }
}
