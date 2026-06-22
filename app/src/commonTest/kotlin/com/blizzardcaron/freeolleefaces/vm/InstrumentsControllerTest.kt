package com.blizzardcaron.freeolleefaces.vm

import com.blizzardcaron.freeolleefaces.activity.ActivityUnit
import com.blizzardcaron.freeolleefaces.instruments.InstrumentsSessionLauncher
import com.blizzardcaron.freeolleefaces.instruments.InstrumentsState
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class FakeLauncher : InstrumentsSessionLauncher {
    override val state: StateFlow<InstrumentsState> = MutableStateFlow(InstrumentsState())
    var started = 0
    var unitChanged = 0
    override fun start() { started++ }
    override fun stop() = Unit
    override fun cycleInstrument() = Unit
    override fun onUnitChanged() { unitChanged++ }
}

class InstrumentsControllerTest {
    @Test fun startWithoutPermissionStillStartsButWarns() {
        val launcher = FakeLauncher()
        val warnings = mutableListOf<String>()
        val c = InstrumentsController(launcher, Prefs(MapSettings()), { false }, { warnings += it })
        c.onStart()
        assertEquals(1, launcher.started)
        assertTrue(warnings.isNotEmpty())
    }

    @Test fun toggleUnitFlipsPrefAndNotifiesLauncher() {
        val launcher = FakeLauncher()
        val prefs = Prefs(MapSettings()).also { it.activityUnit = ActivityUnit.IMPERIAL }
        val c = InstrumentsController(launcher, prefs, { true }, {})
        c.toggleUnit()
        assertEquals(ActivityUnit.METRIC, prefs.activityUnit)
        assertEquals(1, launcher.unitChanged)
    }
}