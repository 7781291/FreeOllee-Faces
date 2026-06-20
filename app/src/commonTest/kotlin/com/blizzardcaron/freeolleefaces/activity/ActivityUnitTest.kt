package com.blizzardcaron.freeolleefaces.activity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ActivityUnitTest {

    @Test fun imperial_distance_converts_meters_to_miles() {
        // 1609.344 m == 1 mile
        assertEquals(1.0, ActivityUnit.IMPERIAL.distance(1609.344), 1e-9)
        assertEquals("mi", ActivityUnit.IMPERIAL.distanceSuffix)
    }

    @Test fun metric_distance_converts_meters_to_km() {
        assertEquals(1.0, ActivityUnit.METRIC.distance(1000.0), 1e-9)
        assertEquals("km", ActivityUnit.METRIC.distanceSuffix)
    }

    @Test fun imperial_pace_scales_sec_per_km_to_sec_per_mile() {
        // a mile is longer than a km, so sec/mile > sec/km by the 1.609344 factor
        assertEquals(300.0 * 1.609344, ActivityUnit.IMPERIAL.paceSecondsPerUnit(300.0), 1e-6)
    }

    @Test fun metric_pace_is_unchanged() {
        assertEquals(300.0, ActivityUnit.METRIC.paceSecondsPerUnit(300.0), 1e-9)
        assertTrue(true)
    }
}
