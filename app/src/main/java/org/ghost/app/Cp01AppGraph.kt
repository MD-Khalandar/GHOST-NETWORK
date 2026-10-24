package org.ghost.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.ghost.simulation.Cp01Simulation

/** Retained through activity recreation; deliberately reset after process death in CP01. */
class Cp01AppGraph : ViewModel() {
    private val simulation = Cp01Simulation()
    val dependencies = GhostAppDependenciesFactory {
        GhostAppDependencies(simulation.transport, simulation.cloudClient, simulation.sosRepository,
            simulation.helperOfferRepository, simulation.cloudCoordinationRepository)
    }.create()

    init {
        viewModelScope.launch { simulation.seedLocalReports() }
    }
}
