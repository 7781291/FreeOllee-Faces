package com.blizzardcaron.freeolleefaces.activity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrackAnalyticsTest {
    private fun p(t: Long, lat: Double, lng: Double, alt: Double? = null) =
        TrackPoint(tMs = t, lat = lat, lng = lng, altM = alt)

    @Test fun elevationSkipsPointsWithoutAltitude() {
        val s = TrackAnalytics.elevation(listOf(p(0, 0.0, 0.0, 100.0), p(1000, 0.001, 0.0), p(2000, 0.002, 0.0, 110.0)))
        assertEquals(2, s.points.size)
        assertEquals(100.0, s.points.first().y, 1e-6)
        assertTrue(s.points.last().x > s.points.first().x)
    }

    @Test fun elevationEmptyWhenNoAltitude() {
        assertEquals(0, TrackAnalytics.elevation(listOf(p(0, 0.0, 0.0), p(1, 0.001, 0.0))).points.size)
    }

    @Test fun speedComputesSegmentRates() {
        // 0.001 deg lat ~= 111 m over 100 s -> ~1.11 m/s
        val s = TrackAnalytics.speed(listOf(p(0, 0.0, 0.0), p(100_000, 0.001, 0.0)))
        assertEquals(1, s.points.size)
        assertTrue(s.points.first().y in 1.0..1.3, "got ${s.points.first().y}")
    }

    @Test fun routeNormalizesIntoUnitBoxAspectPreserved() {
        val r = TrackAnalytics.route(listOf(p(0, 0.0, 0.0), p(1, 0.0, 0.002), p(2, 0.001, 0.002)))
        assertTrue(r.points.all { it.x in 0.0..1.0 && it.y in 0.0..1.0 })
        assertEquals(3, r.points.size)
    }

    @Test fun routeEmptyForSinglePoint() {
        assertEquals(0, TrackAnalytics.route(listOf(p(0, 0.0, 0.0))).points.size)
    }
}
