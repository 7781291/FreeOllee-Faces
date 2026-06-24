package com.blizzardcaron.freeolleefaces.activity

import com.blizzardcaron.freeolleefaces.fakes.FakeActivityTrackStore
import com.blizzardcaron.freeolleefaces.fakes.FakeBleClient
import com.blizzardcaron.freeolleefaces.fakes.FakeSessionAutoSleep
import com.blizzardcaron.freeolleefaces.location.Coords
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActivitySessionEngineLiveModeTest {

    private class Harness {
        val ble = FakeBleClient()
        val store = FakeActivityTrackStore()
        val autoSleep = FakeSessionAutoSleep()
        val prefs = Prefs(MapSettings())
        var clockMs = 0L
        val engine = ActivitySessionEngine(
            ble = ble, store = store, prefs = prefs, autoSleep = autoSleep,
            watchAddress = { "AA:BB" }, now = { clockMs }, newId = { "trk" },
        )
        fun moving(altM: Double, bearing: Float) =
            Coords(0.0, 0.0, 5f, "gps", altM = altM, bearingDeg = bearing, speedMps = 2f)
    }

    @Test fun start_live_defaults_to_orientation_and_not_recording() = runTest {
        val h = Harness()
        h.engine.startLive()
        assertTrue(h.engine.state.value.running)
        assertFalse(h.engine.state.value.recording)
        assertEquals(ActivityMetric.ORIENTATION, h.engine.state.value.selectedMetric)
        assertTrue(h.autoSleep.calls.isEmpty()) // live mode does not touch auto-sleep
    }

    @Test fun live_ingest_populates_heading_and_altitude() = runTest {
        val h = Harness()
        h.engine.startLive()
        h.engine.ingest(h.moving(altM = 1234.0, bearing = 90f), 0L)
        assertEquals(90f, h.engine.state.value.headingDeg)
        assertEquals(1234.0, h.engine.state.value.altitudeM)
    }

    @Test fun live_session_saves_no_track_on_stop() = runTest {
        val h = Harness()
        h.engine.startLive()
        h.engine.ingest(h.moving(altM = 1234.0, bearing = 90f), 0L)
        h.engine.tick(1_000L)
        h.engine.stop()
        assertNull(h.store.latest()) // nothing persisted in live mode
    }

    @Test fun recording_session_still_saves_a_track() = runTest {
        val h = Harness()
        h.engine.start()
        h.engine.ingest(h.moving(altM = 10.0, bearing = 0f), 0L)
        h.clockMs = 5_000L
        h.engine.stop()
        assertNotNull(h.store.latest())
    }
}
