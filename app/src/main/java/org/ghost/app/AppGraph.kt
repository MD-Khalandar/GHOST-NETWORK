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
import org.ghost.security.AndroidSigningIdentity
import org.ghost.transport.nearby.NearbyAdapter

class AppGraph(application: Application) : AndroidViewModel(application) {
    private val db = Room.databaseBuilder(application, GhostDatabase::class.java, "ghost.db").build()
    private val identity by lazy { AndroidSigningIdentity().loadOrCreate() }
    val nearby = NearbyAdapter(application)
    val repository = LocalSosRepository(db, { identity })
    private val exchange = DirectExchange(viewModelScope, nearby, repository) { identity }
    val dependencies = GhostAppDependencies(nearby, UnavailableGateway, repository, UnavailableOffers, UnavailableCoordination)
    private val operation = MutableStateFlow(LocalUiState())
    private data class DraftWrite(val draft: SosDraft, val complete: (() -> Unit)? = null)
    private val draftWrites = Channel<DraftWrite>(Channel.CONFLATED)
    val local: StateFlow<LocalUiState> = combine(repository.observeDraft(), operation, nearby.state, exchange.identities, exchange.message) {
        draft, state, network, identities, message -> state.copy(draft = draft, network = network, peerIdentities = identities, exchangeStatus = message)
    }.catch { emit(LocalUiState(error = "Local storage could not be opened.")) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, LocalUiState())

    init {
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
                    consent = Consent(draft.publicSharing, draft.cloudConsent))
                require(draft.people.isBlank() || payload.peopleCount != null) { "Enter a valid people count." }
                repository.publish(payload).getOrThrow()
                operation.value = LocalUiState(notice = if (draft.publicSharing) "SOS saved. Queued for paired nearby phones." else "SOS saved on this phone only.")
                saved(); exchange.flush()
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (error: Exception) { operation.value = LocalUiState(error = if (error is IllegalArgumentException) error.message else "SOS could not be saved. Check storage and the signing key, then retry.") }
        }
    }
    fun startNearby() {
        viewModelScope.launch {
            try {
                val name = withContext(Dispatchers.IO) { "GHOST ${identity.nodeId.value.take(8)}" }
                nearby.start(name)
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { operation.value = LocalUiState(error = "Nearby could not start. Check the signing key, permissions and Google Play services.") }
        }
    }
    fun stopNearby() { viewModelScope.launch { nearby.stop() } }
    fun actions(start: () -> Unit) = LocalActions({ draft -> if (!operation.value.saving) draftWrites.trySend(DraftWrite(draft)) }, ::saveDraft, ::publish, start, ::stopNearby,
        { endpoint -> viewModelScope.launch { nearby.connect(endpoint) } },
        { endpoint, accept -> viewModelScope.launch { nearby.confirm(endpoint, accept) } },
        nearby::disconnect, exchange::retry)

    override fun onCleared() {
        // SDK stop calls are immediate; the app also stops links when its activity backgrounds.
        nearby.close()
        db.close()
        super.onCleared()
    }
}
