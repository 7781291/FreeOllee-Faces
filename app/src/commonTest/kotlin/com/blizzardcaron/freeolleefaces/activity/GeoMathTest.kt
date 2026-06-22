package com.blizzardcaron.freeolleefaces.activity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeoMathTest {
    @Test
    fun zeroDistanceForSamePoint() {
        assertEquals(0.0, GeoMath.haversineMeters(40.0, -105.0, 40.0, -105.0), 1e-6)
    }

    @Test
    fun knownShortDistanceMatchesHaversine() {
        // ~111.2 m per 0.001 deg latitude near the equator.
        val d = GeoMath.haversineMeters(0.0, 0.0, 0.001, 0.0)
        assertTrue(d in 110.0..113.0, "expected ~111 m, got $d")
    }
}