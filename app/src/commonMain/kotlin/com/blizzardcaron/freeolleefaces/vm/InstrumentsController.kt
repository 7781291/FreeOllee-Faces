package com.blizzardcaron.freeolleefaces.vm

import com.blizzardcaron.freeolleefaces.activity.ActivityUnit
import com.blizzardcaron.freeolleefaces.instruments.InstrumentsSessionLauncher
import com.blizzardcaron.freeolleefaces.instruments.InstrumentsState
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import kotlinx.coroutines.flow.StateFlow

/**
 * VM-facing controller for Instruments mode (sibling of [ActivityController]). Compass and altitude
 * need GPS, so onStart warns (but still starts) when location permission is missing — they render
 * "---" until a fix arrives; pressure and temperature need no location.
 */
class InstrumentsController(
    private val launcher: InstrumentsSessionLauncher,
    private val prefs: Prefs,
    private val hasLocationPermission: () -> Boolean,
    private val showSnackbar: (String) -> Unit,
) {
    val state: StateFlow<InstrumentsState> get() = launcher.state
    val activityUnit: ActivityUnit get() = prefs.activityUnit
    val watchSelected: Boolean get() = prefs.watchAddress != null

    fun onStart() {
        if (!hasLocationPermission()) showSnackbar("Enable location for compass and altitude.")
        launcher.start()
    }

    fun onStop() = launcher.stop()

    fun onMode() = launcher.cycleInstrument()

    fun toggleUnit() {
        prefs.activityUnit =
            if (prefs.activityUnit == ActivityUnit.IMPERIAL) ActivityUnit.METRIC else ActivityUnit.IMPERIAL
        launcher.onUnitChanged()
    }
}
