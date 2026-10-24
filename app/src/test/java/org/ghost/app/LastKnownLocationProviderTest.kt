package org.ghost.app

import android.Manifest
import android.app.Application
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.ghost.domain.model.SosLocation
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LastKnownLocationProviderTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val now = 600L * 1_000_000_000
    @Before fun advanceDeviceClock() { ShadowSystemClock.advanceBy(Duration.ofSeconds(600)) }
    private fun fix(provider: String, ageSeconds: Long) = Location(provider).apply {
        latitude = 12.5; longitude = 44.25
        elapsedRealtimeNanos = now - ageSeconds * 1_000_000_000
        time = System.currentTimeMillis()
    }

    @Test fun rejectsMissingPermissionOldFutureAndInvalidFixes() {
        val manager = context.getSystemService(LocationManager::class.java)
        val shadow = shadowOf(manager)
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val provider = LastKnownLocationProvider(context) { now }
        shadow.setLastKnownLocation(LocationManager.GPS_PROVIDER, fix(LocationManager.GPS_PROVIDER, 1))
        shadowOf(context).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        assertNull(provider.read())
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        for (location in listOf(fix(LocationManager.GPS_PROVIDER, 301), fix(LocationManager.GPS_PROVIDER, -1),
            fix(LocationManager.GPS_PROVIDER, 1).apply { latitude = Double.NaN })) {
            shadow.setLastKnownLocation(LocationManager.GPS_PROVIDER, location)
            assertNull(provider.read())
        }
    }

    @Test fun selectsRecentFixByMonotonicTimeInsteadOfChangedWallClock() {
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val manager = context.getSystemService(LocationManager::class.java)
        val shadow = shadowOf(manager)
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadow.setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        shadow.setLastKnownLocation(LocationManager.GPS_PROVIDER, fix(LocationManager.GPS_PROVIDER, 10).apply {
            time = 1; latitude = 0.0; longitude = 0.0
        })
        shadow.setLastKnownLocation(LocationManager.NETWORK_PROVIDER, fix(LocationManager.NETWORK_PROVIDER, 20))
        assertEquals(SosLocation(0.0, 0.0), LastKnownLocationProvider(context) { now }.read())
    }

    @Test @Config(sdk = [24, 35]) fun requestsFreshFixWithoutCacheAndStopsBothProvidersAfterSuccess() = runTest {
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val shadow = shadowOf(context.getSystemService(LocationManager::class.java))
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadow.setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        val result = async { LastKnownLocationProvider(context) { now }.current() }
        runCurrent()
        assertFalse(result.isCompleted)
        assertTrue(shadow.getLocationUpdateListeners(LocationManager.GPS_PROVIDER).isNotEmpty())
        shadow.simulateLocation(fix(LocationManager.GPS_PROVIDER, 0))
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
        assertEquals(SosLocation(12.5, 44.25), result.await())
        assertTrue(shadow.locationUpdateListeners.isEmpty())
    }

    @Test fun timeoutUsesRecentCacheAndCancellationReleasesSensors() = runTest {
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val shadow = shadowOf(context.getSystemService(LocationManager::class.java))
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadow.setLastKnownLocation(LocationManager.GPS_PROVIDER, fix(LocationManager.GPS_PROVIDER, 120))
        val provider = LastKnownLocationProvider(context) { now }
        val result = async { provider.current() }
        runCurrent()
        assertFalse(result.isCompleted)
        advanceTimeBy(5_001); runCurrent()
        assertEquals(SosLocation(12.5, 44.25), result.await())
        assertTrue(shadow.locationUpdateListeners.isEmpty())
        val cancelled = async { provider.current() }
        runCurrent()
        cancelled.cancelAndJoin()
        assertTrue(shadow.locationUpdateListeners.isEmpty())
    }

    @Test fun missingPermissionDisabledProvidersAndStaleCacheDoNotBlockSending() = runTest {
        val shadow = shadowOf(context.getSystemService(LocationManager::class.java))
        val provider = LastKnownLocationProvider(context) { now }
        shadowOf(context).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        assertNull(provider.current())
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        shadow.setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)
        assertNull(provider.current())
        shadow.setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        shadow.setLastKnownLocation(LocationManager.NETWORK_PROVIDER, fix(LocationManager.NETWORK_PROVIDER, 301))
        val result = async { provider.current() }
        runCurrent(); advanceTimeBy(5_001); runCurrent()
        assertNull(result.await())
        assertTrue(shadow.locationUpdateListeners.isEmpty())
    }
}
