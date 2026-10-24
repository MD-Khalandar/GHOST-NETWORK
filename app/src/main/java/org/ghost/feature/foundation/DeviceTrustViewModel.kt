package org.ghost.feature.foundation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ghost.BuildConfig
import org.ghost.protocol.WireCodec
import org.ghost.security.AndroidSigningIdentity
import org.ghost.security.SigningIdentity

enum class IdentityStatus { CHECKING, READY, UNAVAILABLE }
enum class CloudTrustStatus { NOT_CONFIGURED, PINNED, INVALID }
enum class OperatorAccess { SIGN_IN_REQUIRED, NOT_AUTHORIZED, AUTHENTICATED_OPERATOR }
data class DeviceTrustState(
    val identity: IdentityStatus = IdentityStatus.CHECKING,
    val nodeId: String? = null,
    val cloudTrust: CloudTrustStatus = CloudTrustStatus.NOT_CONFIGURED,
    val cloudNodeId: String? = null,
    // Only an authenticated server session may set this; no local role switch exists.
    val operatorAccess: OperatorAccess = OperatorAccess.SIGN_IN_REQUIRED,
)

class DeviceTrustViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(DeviceTrustState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    init { retry() }

    fun retry() {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            mutableState.value = DeviceTrustState()
            val identity = try { withContext(Dispatchers.IO) { AndroidSigningIdentity().loadOrCreate() } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            val cloud = try {
                BuildConfig.CLOUD_PUBLIC_KEY.takeIf { it.isNotEmpty() }?.let { encoded ->
                    WireCodec.unbase64(encoded, 91).also(SigningIdentity::decodePublicKey)
                }
            } catch (_: Exception) { null }
            mutableState.value = DeviceTrustState(
                identity = if (identity == null) IdentityStatus.UNAVAILABLE else IdentityStatus.READY,
                nodeId = identity?.nodeId?.value,
                cloudTrust = when {
                    BuildConfig.CLOUD_PUBLIC_KEY.isEmpty() -> CloudTrustStatus.NOT_CONFIGURED
                    cloud == null -> CloudTrustStatus.INVALID
                    else -> CloudTrustStatus.PINNED
                },
                cloudNodeId = cloud?.let { SigningIdentity.nodeId(it).value },
            )
        }
    }
}
