package com.blizzardcaron.freeolleefaces.activity

import com.blizzardcaron.freeolleefaces.fakes.FakeActivityTrackStore
import com.blizzardcaron.freeolleefaces.fakes.FakeSessionAutoSleep
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActivityRecoveryTest {

    private fun prefsActive(active: Boolean): Prefs =
        Prefs(MapSettings()).apply { activityActive = active }

    @Test fun no_recovery_when_not_active() = runTest {
        val prefs = prefsActive(false)
        val autoSleep = FakeSessionAutoSleep()
        val recovered = ActivityRecovery.recoverIfStranded(
            prefs, FakeActivityTrackStore(), autoSleep, "AA:BB", sessionRunning = false,
        )
        assertFalse(recovered)
        assertTrue(autoSleep.calls.isEmpty())
    }

    @Test fun no_recovery_when_session_is_running() = runTest {
        val prefs = prefsActive(true)
        val autoSleep = FakeSessionAutoSleep()
        val recovered = ActivityRecovery.recoverIfStranded(
            prefs, FakeActivityTrackStore(), autoSleep, "AA:BB", sessionRunning = true,
        )
        assertFalse(recovered)
        assertTrue(autoSleep.calls.isEmpty())
    }

    @Test fun stranded_with_watch_restores_autosleep_and_marks_track_abnormal() = runTest {
        val prefs = prefsActive(true)
        val store = FakeActivityTrackStore()
        store.save(
            ActivityTrack(
                id = "t", startedAtMs = 0L, endedAtMs = null, unit = ActivityUnit.IMPERIAL,
                points = listOf(TrackPoint(tMs = 5_000L, lat = 0.0, lng = 0.0)),
            ),
        )
        val autoSleep = FakeSessionAutoSleep()

        val recovered = ActivityRecovery.recoverIfStranded(
            prefs, store, autoSleep, "AA:BB", sessionRunning = false,
        )

        assertTrue(recovered)
        assertEquals(listOf("restore(AA:BB)"), autoSleep.calls)
        val saved = store.latest()!!
        assertTrue(saved.endedAbnormally)
        assertEquals(5_000L, saved.endedAtMs)
    }

    @Test fun stranded_without_watch_just_clears_flag() = runTest {
        val prefs = prefsActive(true)
        val autoSleep = FakeSessionAutoSleep()
        val recovered = ActivityRecovery.recoverIfStranded(
            prefs, FakeActivityTrackStore(), autoSleep, null, sessionRunning = false,
        )
        assertTrue(recovered)
        assertFalse(prefs.activityActive)
        assertTrue(autoSleep.calls.isEmpty())
    }
}
