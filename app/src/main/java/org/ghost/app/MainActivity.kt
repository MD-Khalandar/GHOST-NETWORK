package org.ghost.app

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import org.ghost.design.GhostTheme
import org.ghost.feature.foundation.FoundationViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        val graph = ViewModelProvider(this)[Cp01AppGraph::class.java]
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
            GhostTheme { GhostApp(state, screens::retry) }
        }
    }
}
