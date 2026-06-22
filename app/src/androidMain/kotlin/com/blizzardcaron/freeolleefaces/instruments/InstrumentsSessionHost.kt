package com.blizzardcaron.freeolleefaces.instruments

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Shares the running engine's state with the UI and tracks whether the service is alive. */
object InstrumentsSessionHost {
    val mutableState = MutableStateFlow(InstrumentsState())
    val state: StateFlow<InstrumentsState> = mutableState

    @Volatile var isRunning: Boolean = false
}
