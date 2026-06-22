package com.blizzardcaron.freeolleefaces.activity

import kotlin.test.Test
import kotlin.test.assertEquals

class ActivityRetentionTest {
    private val dayMs = 24L * 60 * 60 * 1000
    private fun track(id: String, endedAtMs: Long?) =
        ActivityTrack(id = id, startedAtMs = 0, endedAtMs = endedAtMs, unit = ActivityUnit.METRIC)

    @Test fun selectsOnlyTracksOlderThanCutoff() {
        val now = 30 * dayMs
        val ids = ActivityRetention.idsToDelete(
            listOf(
                track("old", now - 8 * dayMs),
                track("fresh", now - 1 * dayMs),
                track("running", null),
            ),
            now,
        )
        assertEquals(listOf("old"), ids)
    }

    @Test fun cutoffIsSevenDaysBeforeNow() {
        val now = 100 * dayMs
        assertEquals(now - 7 * dayMs, ActivityRetention.cutoffMs(now))
    }
}
