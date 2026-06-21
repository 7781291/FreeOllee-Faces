package com.blizzardcaron.freeolleefaces.activity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ActivityMetricRenderTest {

    private fun state(distM: Double = 0.0, pace: Double? = null, elapsedMs: Long = 0L) =
        ActivityState(distanceMeters = distM, recentPaceSecPerKm = pace, elapsedMs = elapsedMs)

    @Test fun every_render_is_at_most_six_ascii_chars() {
        val states = listOf(
            state(distM = 5172.0, pace = 317.0, elapsedMs = 1_565_000L),
            state(distM = 167_000.0, pace = null, elapsedMs = 3_925_000L),
        )
        for (s in states) for (m in ActivityMetric.entries) for (u in ActivityUnit.entries) {
            val out = m.render(s, u)
            assertTrue(out.length <= 6, "'$out' (${out.length}) for $m/$u")
            assertTrue(out.all { it.code in 0..127 }, "non-ascii in '$out'")
        }
    }

    @Test fun pace_warmup_shows_placeholder() {
        assertEquals("P --:-", ActivityMetric.PACE.render(state(pace = null), ActivityUnit.IMPERIAL))
    }

    @Test fun pace_single_digit_minutes_has_leading_space() {
        // 8:30 /mi -> 510 sec/mi -> 510/1.609344 = 316.9 sec/km
        val secPerKm = (8 * 60 + 30) / 1.609344
        assertEquals("P 8:30", ActivityMetric.PACE.render(state(pace = secPerKm), ActivityUnit.IMPERIAL))
    }

    @Test fun pace_double_digit_minutes_fills_six() {
        // 12:05 /mi
        val secPerKm = (12 * 60 + 5) / 1.609344
        assertEquals("P12:05", ActivityMetric.PACE.render(state(pace = secPerKm), ActivityUnit.IMPERIAL))
    }

    @Test fun distance_renders_two_then_one_then_zero_decimals() {
        assertEquals("3.21mi", ActivityMetric.DISTANCE.render(state(distM = 3.21 * 1609.344), ActivityUnit.IMPERIAL))
        assertEquals("12.4mi", ActivityMetric.DISTANCE.render(state(distM = 12.4 * 1609.344), ActivityUnit.IMPERIAL))
        assertEquals("104mi", ActivityMetric.DISTANCE.render(state(distM = 104.0 * 1609.344), ActivityUnit.IMPERIAL))
    }

    @Test fun time_minutes_seconds_under_an_hour() {
        assertEquals("T26:05", ActivityMetric.TIME.render(state(elapsedMs = (26 * 60 + 5) * 1000L), ActivityUnit.IMPERIAL))
        assertEquals("T05:21", ActivityMetric.TIME.render(state(elapsedMs = (5 * 60 + 21) * 1000L), ActivityUnit.IMPERIAL))
    }

    @Test fun time_rolls_to_hours_marker_at_one_hour() {
        assertEquals("1:05h", ActivityMetric.TIME.render(state(elapsedMs = (65 * 60) * 1000L), ActivityUnit.IMPERIAL))
    }

    @Test fun next_cycles_pace_distance_time() {
        assertEquals(ActivityMetric.DISTANCE, ActivityMetric.PACE.next())
        assertEquals(ActivityMetric.TIME, ActivityMetric.DISTANCE.next())
        assertEquals(ActivityMetric.PACE, ActivityMetric.TIME.next())
    }
}
