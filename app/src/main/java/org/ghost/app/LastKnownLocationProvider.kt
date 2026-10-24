package org.ghost.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import org.ghost.domain.model.SosLocation

class LastKnownLocationProvider(private val context: Context) {
    fun read(): SosLocation? {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        return try {
            manager.getProviders(true).asSequence()
                .mapNotNull { provider -> manager.getLastKnownLocation(provider) }
                .maxByOrNull(Location::getTime)
                ?.takeIf { it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 }
                ?.let { SosLocation(it.latitude, it.longitude) }
        } catch (_: SecurityException) {
            null
        }
    }
}
