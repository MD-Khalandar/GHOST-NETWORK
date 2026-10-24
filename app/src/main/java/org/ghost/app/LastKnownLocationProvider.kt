package org.ghost.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import org.ghost.domain.model.SosLocation

class LastKnownLocationProvider(
    private val context: Context,
    private val elapsedNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
) {
    private fun permitted(): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    /** Bound sensor work to this call; cancellation/timeout releases every provider. */
    suspend fun current(timeoutMs: Long = 5_000): SosLocation? {
        if (!permitted()) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = try { manager.getProviders(true).filter { it != LocationManager.PASSIVE_PROVIDER } }
            catch (_: SecurityException) { return null }
        if (providers.isEmpty()) return read()
        val fresh = withTimeoutOrNull(timeoutMs) {
            callbackFlow<SosLocation> {
                val signals = providers.map { CancellationSignal() }
                val remaining = AtomicInteger(providers.size)
                fun received(location: Location?) {
                    if (location != null && valid(location, FRESH_AGE_NANOS)) {
                        trySend(SosLocation(location.latitude, location.longitude))
                        close()
                    } else if (remaining.decrementAndGet() == 0) close()
                }
                providers.forEachIndexed { index, provider ->
                    try {
                        LocationManagerCompat.getCurrentLocation(manager, provider, signals[index],
                            ContextCompat.getMainExecutor(context), ::received)
                    } catch (_: SecurityException) { received(null) }
                      catch (_: IllegalArgumentException) { received(null) }
                }
                awaitClose { signals.forEach { it.cancel() } }
            }.firstOrNull()
        }
        return fresh ?: read()
    }

    fun read(): SosLocation? {
        if (!permitted()) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        return try {
            manager.getProviders(true).asSequence()
                .mapNotNull { provider -> try { manager.getLastKnownLocation(provider) }
                    catch (_: SecurityException) { null } catch (_: IllegalArgumentException) { null } }
                .filter { valid(it, MAX_AGE_NANOS) }
                .maxByOrNull(Location::getElapsedRealtimeNanos)
                ?.let { SosLocation(it.latitude, it.longitude) }
        } catch (_: SecurityException) {
            null
        }
    }

    private fun valid(location: Location, maxAge: Long): Boolean = location.elapsedRealtimeNanos > 0 &&
        elapsedNanos() - location.elapsedRealtimeNanos in 0..maxAge &&
        location.latitude in -90.0..90.0 && location.longitude in -180.0..180.0

    private companion object {
        const val MAX_AGE_NANOS = 5L * 60 * 1_000_000_000
        const val FRESH_AGE_NANOS = 30L * 1_000_000_000
    }
}
