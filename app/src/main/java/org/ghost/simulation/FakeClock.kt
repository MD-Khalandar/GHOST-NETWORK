package org.ghost.simulation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Manually advanced fixture time. No wall clock, timers, or production clock contract. */
class FakeClock(initialTimeMs: Long = Cp01Fixtures.REPORTED_AT_MS) {
    init {
        require(initialTimeMs >= 0) { "Fixture time must be non-negative" }
    }

    private val mutableTime = MutableStateFlow(initialTimeMs)
    val time: StateFlow<Long> = mutableTime.asStateFlow()
    val nowMs: Long get() = time.value

    @Synchronized
    fun advanceBy(milliseconds: Long) {
        require(milliseconds >= 0) { "Fixture time cannot move backwards" }
        mutableTime.value = Math.addExact(nowMs, milliseconds)
    }
}
