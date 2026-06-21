package com.blizzardcaron.freeolleefaces.activity

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Shares the running engine's state with the UI and tracks whether the service is alive. */
object ActivitySessionHost {
    val mutableState = MutableStateFlow(ActivityState())
    val state: StateFlow<ActivityState> = mutableState

    @Volatile var isRunning: Boolean = false
}
