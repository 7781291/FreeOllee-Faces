package com.blizzardcaron.freeolleefaces.activity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActivityTrackJsonTest {

    @Test fun round_trips_a_full_track() {
        val track = ActivityTrack(
            id = "abc",
            startedAtMs = 1_000L,
            endedAtMs = 5_000L,
            endedAbnormally = false,
            unit = ActivityUnit.IMPERIAL,
            points = listOf(
                TrackPoint(tMs = 1_000L, lat = 1.0, lng = 2.0, accuracyM = 5f, altM = 100.0),
                TrackPoint(tMs = 2_000L, lat = 1.001, lng = 2.0),
            ),
            summary = ActivitySummary(
                distanceM = 111.3, movingTimeMs = 4_000L, elapsedTimeMs = 4_000L, avgPaceSecPerKm = 300.0,
            ),
        )
        val decoded = ActivityTrackJson.decode(ActivityTrackJson.encode(track))
        assertEquals(track, decoded)
    }

    @Test fun round_trips_an_abnormal_track_with_null_fields() {
        val track = ActivityTrack(
            id = "x", startedAtMs = 0L, endedAtMs = null, endedAbnormally = true,
            unit = ActivityUnit.METRIC,
            points = listOf(TrackPoint(tMs = 0L, lat = 0.0, lng = 0.0)),
            summary = null,
        )
        val decoded = ActivityTrackJson.decode(ActivityTrackJson.encode(track))
        assertEquals(track, decoded)
        assertTrue(decoded!!.endedAbnormally)
        assertNull(decoded.points.first().altM)
    }

    @Test fun decode_of_garbage_is_null_not_thrown() {
        assertNull(ActivityTrackJson.decode("not json"))
        assertNull(ActivityTrackJson.decode(null))
        assertNull(ActivityTrackJson.decode(""))
    }
}
