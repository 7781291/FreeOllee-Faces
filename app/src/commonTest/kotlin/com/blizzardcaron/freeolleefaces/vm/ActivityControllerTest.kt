package com.blizzardcaron.freeolleefaces.vm

import com.blizzardcaron.freeolleefaces.activity.ActivitySessionLauncher
import com.blizzardcaron.freeolleefaces.activity.ActivityState
import com.blizzardcaron.freeolleefaces.activity.ActivityUnit
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ActivityControllerTest {

    private class FakeLauncher : ActivitySessionLauncher {
        override val state: StateFlow<ActivityState> = MutableStateFlow(ActivityState())
        val calls = mutableListOf<String>()
        override fun start() { calls += "start" }
        override fun stop() { calls += "stop" }
        override fun cycleMetric() { calls += "cycle" }
        override fun setUnit(unit: ActivityUnit) { calls += "setUnit($unit)" }
    }

    private fun controller(
        launcher: FakeLauncher,
        prefs: Prefs,
        permission: Boolean,
        snackbars: MutableList<String>,
    ) = ActivityController(
        launcher = launcher,
        prefs = prefs,
        hasLocationPermission = { permission },
        showSnackbar = { snackbars += it },
    )

    @Test fun onStart_without_permission_does_not_launch_and_warns() {
        val launcher = FakeLauncher()
        val snackbars = mutableListOf<String>()
        controller(launcher, Prefs(MapSettings()), permission = false, snackbars).onStart()
        assertTrue(launcher.calls.isEmpty())
        assertEquals(1, snackbars.size)
    }

    @Test fun onStart_with_permission_launches_even_without_a_watch() {
        val launcher = FakeLauncher()
        controller(launcher, Prefs(MapSettings()), permission = true, mutableListOf()).onStart()
        assertEquals(listOf("start"), launcher.calls)
    }

    @Test fun toggleUnit_flips_pref_and_pushes_to_launcher() {
        val launcher = FakeLauncher()
        val prefs = Prefs(MapSettings()) // defaults IMPERIAL
        val c = controller(launcher, prefs, permission = true, mutableListOf())
        c.toggleUnit()
        assertEquals(ActivityUnit.METRIC, prefs.activityUnit)
        assertEquals(listOf("setUnit(METRIC)"), launcher.calls)
    }

    @Test fun onMode_and_onStop_delegate() {
        val launcher = FakeLauncher()
        val c = controller(launcher, Prefs(MapSettings()), permission = true, mutableListOf())
        c.onMode(); c.onStop()
        assertEquals(listOf("cycle", "stop"), launcher.calls)
    }
}
