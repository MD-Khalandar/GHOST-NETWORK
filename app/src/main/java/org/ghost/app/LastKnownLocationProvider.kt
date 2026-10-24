package org.ghost.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import org.ghost.domain.model.SosLocation

class LastKnownLocationProvider(
    private val context: Context,
    private val elapsedNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
) {
    fun read(): SosLocation? {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val now = elapsedNanos()
        return try {
            manager.getProviders(true).asSequence()
                .mapNotNull { provider -> manager.getLastKnownLocation(provider) }
                .filter { it.elapsedRealtimeNanos > 0 && now - it.elapsedRealtimeNanos in 0..MAX_AGE_NANOS &&
                    it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 }
                .maxByOrNull(Location::getElapsedRealtimeNanos)
                ?.let { SosLocation(it.latitude, it.longitude) }
        } catch (_: SecurityException) {
            null
        }
    }

    private companion object { const val MAX_AGE_NANOS = 5L * 60 * 1_000_000_000 }
}
