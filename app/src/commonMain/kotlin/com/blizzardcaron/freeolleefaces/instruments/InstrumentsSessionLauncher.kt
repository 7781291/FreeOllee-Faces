package com.blizzardcaron.freeolleefaces.instruments

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the VM controller calls to drive an Instruments session. Android impl routes to the service. */
interface InstrumentsSessionLauncher {
    val state: StateFlow<InstrumentsState>
    fun start()
    fun stop()
    fun cycleInstrument()
    fun onUnitChanged()
}

/** Inert launcher: idle state, controls are no-ops. Default for tests and watch-less construction. */
object NoopInstrumentsSessionLauncher : InstrumentsSessionLauncher {
    override val state: StateFlow<InstrumentsState> = MutableStateFlow(InstrumentsState())
    override fun start() = Unit
    override fun stop() = Unit
    override fun cycleInstrument() = Unit
    override fun onUnitChanged() = Unit
}
