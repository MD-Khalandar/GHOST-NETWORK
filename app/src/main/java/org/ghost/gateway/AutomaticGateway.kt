package org.ghost.gateway

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.ghost.gateway.api.GatewayReachability

data class AutomaticConnection(val available: Boolean, val active: Boolean = false, val online: Boolean = false,
                               val message: String = "Tap Connect to GHOST to connect nearby and online.")

/** Only a user's foreground connection and Wi-Fi enable enrollment or online transfers. */
class AutomaticGateway(private val scope: CoroutineScope, private val settings: GatewaySettings,
    private val serverUrl: String, private val client: GatewayEnrollmentClient?, enabled: Flow<Boolean>, wifi: Flow<Boolean>,
    private val reachability: (GatewayReachability) -> Unit, private val now: () -> Long = System::currentTimeMillis) {
    private val refresh = MutableStateFlow(0)
    private val available = serverUrl.isNotBlank() && client != null
    private val mutableState = MutableStateFlow(AutomaticConnection(available))
    val state = mutableState.asStateFlow()
    init {
        scope.launch {
            try { settings.load() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* A new device-bound session can replace unusable saved credentials. */ }
            combine(enabled, wifi, refresh) { active, connected, _ -> active to connected }
                .collectLatest { (active, connected) ->
                    reachability(GatewayReachability.UNKNOWN)
                    mutableState.value = AutomaticConnection(available, active = active, message = when {
                        !available -> "Online connection isn't set up for this app build. Nearby sharing still works."
                        !active -> "Tap Connect to GHOST to connect nearby and online."
                        !connected -> "Waiting for Wi-Fi. Nearby requests can still be shared."
                        else -> "Connecting to the GHOST server…"
                    })
                    if (!available || !active || !connected) return@collectLatest
                    var backoff = 5000L
                    while (currentCoroutineContext().isActive) {
                        try {
                            val saved = settings.config.value?.takeIf {
                                it.serverUrl == serverUrl && (it.expiresAtMs ?: 0) > now() + 300000
                            }
                            val config = saved ?: client!!.enroll(serverUrl).also { settings.saveAutomatic(it) }
                            client!!.checkSession(config)
                            reachability(GatewayReachability.ONLINE)
                            mutableState.value = AutomaticConnection(true, active = true, online = true, message = "Connected to GHOST — online.")
                            backoff = 5000
                            delay(30000)
                        } catch (cancelled: CancellationException) { throw cancelled }
                          catch (error: Exception) {
                            val failure = error as? GatewayFailure
                            if (failure?.authentication == true) clearSession()
                            reachability(if (failure?.authentication == true) GatewayReachability.AUTHENTICATION_FAILED else GatewayReachability.OFFLINE)
                            mutableState.value = AutomaticConnection(true, active = true, message = when (failure?.code) {
                                "DEVICE_REVOKED" -> "This phone can't connect to online sharing. Nearby sharing still works."
                                "UNTRUSTED_SERVER" -> "Couldn't verify the GHOST server. Nearby sharing still works."
                                else -> "Trying to reconnect to the GHOST server. Requests stay saved on this phone."
                            })
                            delay(backoff); backoff = (backoff * 2).coerceAtMost(60000)
                        }
                    }
                }
        }
    }
    fun retry(clearSession: Boolean = false) {
        scope.launch {
            if (clearSession && available) clearSession()
            refresh.value += 1
        }
    }
    private suspend fun clearSession() {
        try { settings.clear() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Storage may recover; never fall back to an unencrypted credential. */ }
    }
}
