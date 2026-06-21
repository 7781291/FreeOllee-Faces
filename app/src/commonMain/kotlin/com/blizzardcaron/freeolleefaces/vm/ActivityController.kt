package com.blizzardcaron.freeolleefaces.vm

import com.blizzardcaron.freeolleefaces.activity.ActivitySessionLauncher
import com.blizzardcaron.freeolleefaces.activity.ActivityState
import com.blizzardcaron.freeolleefaces.activity.ActivityUnit
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import kotlinx.coroutines.flow.StateFlow

/**
 * VM-facing controller for activity mode (sibling of TimerController/ComplicationController). Thin:
 * gates Start on location permission, flips the unit pref, and delegates lifecycle to the injected
 * [ActivitySessionLauncher]; the live [state] is the launcher's (the running engine's) flow.
 */
class ActivityController(
    private val launcher: ActivitySessionLauncher,
    private val prefs: Prefs,
    private val hasLocationPermission: () -> Boolean,
    private val showSnackbar: (String) -> Unit,
) {
    val state: StateFlow<ActivityState> get() = launcher.state
    val activityUnit: ActivityUnit get() = prefs.activityUnit
    val watchSelected: Boolean get() = prefs.watchAddress != null

    fun onStart() {
        if (!hasLocationPermission()) {
            showSnackbar("Enable location to track an activity.")
            return
        }
        launcher.start()
    }

    fun onStop() = launcher.stop()

    fun onMode() = launcher.cycleMetric()

    fun toggleUnit() {
        val next = if (prefs.activityUnit == ActivityUnit.IMPERIAL) ActivityUnit.METRIC else ActivityUnit.IMPERIAL
        prefs.activityUnit = next
        launcher.setUnit(next)
    }
}
