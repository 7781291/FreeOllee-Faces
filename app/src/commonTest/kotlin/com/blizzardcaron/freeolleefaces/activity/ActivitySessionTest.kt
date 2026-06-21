package com.blizzardcaron.freeolleefaces.activity

import com.blizzardcaron.freeolleefaces.location.Coords
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActivitySessionTest {

    private fun fix(lat: Double, lng: Double, acc: Float? = 5f) = Coords(lat, lng, acc, "gps")

    @Test fun accumulates_haversine_distance_between_accepted_fixes() {
        val s = ActivitySession(startedAtMs = 0L)
        s.onSample(fix(0.0, 0.0), 0L)
        s.onSample(fix(0.001, 0.0), 10_000L) // ~111.3 m north
        assertEquals(111.3, s.distanceMeters, 1.0)
    }

    @Test fun drops_low_accuracy_fixes() {
        val s = ActivitySession(startedAtMs = 0L)
        s.onSample(fix(0.0, 0.0), 0L)
        s.onSample(fix(0.001, 0.0, acc = 100f), 10_000L) // accuracy worse than 30 m gate
        assertEquals(0.0, s.distanceMeters, 1e-9)
    }

    @Test fun ignores_sub_minimum_jitter() {
        val s = ActivitySession(startedAtMs = 0L)
        s.onSample(fix(0.0, 0.0), 0L)
        s.onSample(fix(0.000005, 0.0), 2_000L) // ~0.55 m, below the 2 m floor
        assertEquals(0.0, s.distanceMeters, 1e-9)
    }

    @Test fun pace_is_null_until_window_has_enough_span() {
        val s = ActivitySession(startedAtMs = 0L)
        s.onSample(fix(0.0, 0.0), 0L)
        assertNull(s.recentPaceSecPerKm(0L))
    }

    @Test fun pace_reflects_recent_speed() {
        val s = ActivitySession(startedAtMs = 0L)
        // 200 m over 60 s == 0.2 km in 60 s == 300 sec/km
        s.onSample(fix(0.0, 0.0), 0L)
        s.onSample(fix(0.0017986, 0.0), 60_000L) // ~200 m north
        val pace = s.recentPaceSecPerKm(60_000L)
        assertTrue(pace != null && kotlin.math.abs(pace - 300.0) < 15.0, "pace=$pace")
    }

    @Test fun state_carries_distance_elapsed_and_selected_metric() {
        val s = ActivitySession(startedAtMs = 1_000L)
        s.onSample(fix(0.0, 0.0), 1_000L)
        val st = s.state(ActivityMetric.DISTANCE, 4_000L)
        assertEquals(ActivityMetric.DISTANCE, st.selectedMetric)
        assertEquals(3_000L, st.elapsedMs)
        assertTrue(st.running)
    }
}
