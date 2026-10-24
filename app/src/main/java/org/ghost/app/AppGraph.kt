package org.ghost.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.ghost.simulation.DemoEnvironment

/** Retained through activity recreation; reset after process death while using demo data. */
class AppGraph : ViewModel() {
    private val simulation = DemoEnvironment()
    val dependencies = GhostAppDependencies(simulation.transport, simulation.cloudClient, simulation.sosRepository,
        simulation.helperOfferRepository, simulation.cloudCoordinationRepository)

    init {
        viewModelScope.launch { simulation.seedLocalReports() }
    }
}
