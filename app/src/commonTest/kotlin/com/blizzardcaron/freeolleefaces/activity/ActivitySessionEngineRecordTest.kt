package com.blizzardcaron.freeolleefaces.activity

import com.blizzardcaron.freeolleefaces.fakes.FakeActivityTrackStore
import com.blizzardcaron.freeolleefaces.fakes.FakeBleClient
import com.blizzardcaron.freeolleefaces.fakes.FakeSessionAutoSleep
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ActivitySessionEngineRecordTest {
    private fun engine(store: FakeActivityTrackStore) = ActivitySessionEngine(
        ble = FakeBleClient(), store = store, prefs = Prefs(MapSettings()),
        autoSleep = FakeSessionAutoSleep(), watchAddress = { "AA:BB" }, now = { 0L }, newId = { "trk" },
    )

    @Test fun begin_recording_upgrades_live_session() = runTest {
        val store = FakeActivityTrackStore()
        val e = engine(store)
        e.startLive()
        assertFalse(e.state.value.recording)
        e.beginRecording()
        assertTrue(e.state.value.recording)
        assertEquals(ActivityMetric.PACE, e.state.value.selectedMetric)
        e.stop()
        assertNotNull(store.latest())
    }

    @Test fun begin_recording_cold_starts_when_idle() = runTest {
        val store = FakeActivityTrackStore()
        val e = engine(store)
        e.beginRecording()
        assertTrue(e.state.value.recording)
    }
}
