package com.blizzardcaron.freeolleefaces.vm

import com.blizzardcaron.freeolleefaces.activity.ActivityMetric
import com.blizzardcaron.freeolleefaces.activity.ActivityMetricsConfig
import com.blizzardcaron.freeolleefaces.activity.ActivityMetricsRepository
import com.blizzardcaron.freeolleefaces.activity.ActivityMode
import com.blizzardcaron.freeolleefaces.activity.ActivitySessionLauncher
import com.blizzardcaron.freeolleefaces.activity.ActivityState
import com.blizzardcaron.freeolleefaces.activity.ActivityUnit
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import kotlinx.coroutines.flow.StateFlow

/**
 * VM-facing controller for activity mode (sibling of TimerController/ComplicationController). Thin:
 * gates Start on location permission, flips the unit pref, and delegates lifecycle to the injected
 * [ActivitySessionLauncher]; the live [state] is the launcher's (the running engine's) flow. Also
 * exposes the per-mode metric configuration, delegating reorder/enable ops to [metricsRepo].
 */
class ActivityController(
    private val launcher: ActivitySessionLauncher,
    private val prefs: Prefs,
    private val hasLocationPermission: () -> Boolean,
    private val showSnackbar: (String) -> Unit,
    private val metricsRepo: ActivityMetricsRepository,
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

    /** Open the non-recording live glance (compass/altitude); same location gate as recording. */
    fun onShowLive() {
        if (!hasLocationPermission()) {
            showSnackbar("Enable location for the instrument glance.")
            return
        }
        launcher.startLive()
    }

    fun onStop() = launcher.stop()

    fun onMode() = launcher.cycleMetric()

    fun toggleUnit() {
        val next = if (prefs.activityUnit == ActivityUnit.IMPERIAL) ActivityUnit.METRIC else ActivityUnit.IMPERIAL
        prefs.activityUnit = next
        launcher.setUnit(next)
    }

    /** The current per-mode metric configuration (recording + glance), read through to [metricsRepo]. */
    fun metricsConfig(): ActivityMetricsConfig = metricsRepo.get()

    fun moveMetricUp(mode: ActivityMode, index: Int) = metricsRepo.moveUp(mode, index)

    fun moveMetricDown(mode: ActivityMode, index: Int) = metricsRepo.moveDown(mode, index)

    fun setMetricEnabled(mode: ActivityMode, metric: ActivityMetric, enabled: Boolean) =
        metricsRepo.setEnabled(mode, metric, enabled)
}
