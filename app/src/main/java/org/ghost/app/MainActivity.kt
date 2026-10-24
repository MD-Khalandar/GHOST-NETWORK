package org.ghost.app

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import org.ghost.design.GhostTheme
import org.ghost.feature.foundation.FoundationViewModel
import org.ghost.feature.foundation.DeviceTrustViewModel

class MainActivity : ComponentActivity() {
    private lateinit var graph: AppGraph
    private val locationPermissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (::graph.isInitialized) {
            if (graph.nearby.missingPermissions().isEmpty()) graph.startNearby() else graph.nearby.permissionDenied()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        graph = (application as GhostApplication).graph
        val trustViewModel = ViewModelProvider(this)[DeviceTrustViewModel::class.java]
        val screens = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == FoundationViewModel::class.java)
                val dependencies = graph.dependencies
                return FoundationViewModel(dependencies.sosRepository, dependencies.helperOfferRepository,
                    dependencies.cloudCoordinationRepository, dependencies.transport, dependencies.gatewayClient) as T
            }
        })[FoundationViewModel::class.java]
        setContent {
            val state by screens.state.collectAsStateWithLifecycle()
            val trust by trustViewModel.state.collectAsStateWithLifecycle()
            val local by graph.local.collectAsStateWithLifecycle()
            GhostTheme { GhostApp(state, screens::retry, trust, trustViewModel::retry, local, graph.actions({
                val missing = graph.nearby.missingPermissions().toMutableList()
                if (Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(this,
                    android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                    missing.add(android.Manifest.permission.POST_NOTIFICATIONS)
                if (missing.isEmpty()) graph.startNearby() else permissionRequest.launch(missing.toTypedArray())
            }, {
                locationPermissionRequest.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
            })) }
        }
    }
}
