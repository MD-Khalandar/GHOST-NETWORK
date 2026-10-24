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
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (::graph.isInitialized) {
            if (graph.nearby.missingPermissions().isEmpty()) graph.startNearby() else graph.nearby.permissionDenied()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        graph = ViewModelProvider(this)[AppGraph::class.java]
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
            GhostTheme { GhostApp(state, screens::retry, trust, trustViewModel::retry, local, graph.actions {
                val missing = graph.nearby.missingPermissions()
                if (missing.isEmpty()) graph.startNearby() else permissionRequest.launch(missing)
            }) }
        }
    }
    override fun onStop() {
        if (::graph.isInitialized && !isChangingConfigurations) graph.stopNearby()
        super.onStop()
    }
}
