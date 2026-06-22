package com.blizzardcaron.freeolleefaces.instruments

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

/** Routes VM controller calls to [InstrumentsSessionService]; exposes the host's shared state. */
class AndroidInstrumentsSessionLauncher(private val context: Context) : InstrumentsSessionLauncher {
    override val state: StateFlow<InstrumentsState> = InstrumentsSessionHost.state
    override fun start() = InstrumentsSessionService.start(context)
    override fun stop() = InstrumentsSessionService.stop(context)
    override fun cycleInstrument() = InstrumentsSessionService.cycle(context)
    override fun onUnitChanged() = InstrumentsSessionService.setUnit(context)
}
