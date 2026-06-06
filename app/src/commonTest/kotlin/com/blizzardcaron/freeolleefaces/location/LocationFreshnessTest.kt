package com.blizzardcaron.freeolleefaces.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocationFreshnessTest {

    private val now = 1_000_000_000_000L
    private val day = 24 * 60 * 60 * 1000L

    @Test fun staleWhenNeverFetched() {
        assertTrue(isLocationStale(fetchedMs = null, nowMs = now))
    }

    @Test fun freshWithinWeek() {
        assertFalse(isLocationStale(fetchedMs = now - 3 * day, nowMs = now))
    }

    @Test fun staleAtOrPastWeek() {
        assertTrue(isLocationStale(fetchedMs = now - 7 * day, nowMs = now))
        assertTrue(isLocationStale(fetchedMs = now - 8 * day, nowMs = now))
    }

    @Test fun labelNullWhenNeverFetched() {
        assertNull(freshnessLabel(fetchedMs = null, nowMs = now))
    }

    @Test fun labelBuckets() {
        assertEquals("just now", freshnessLabel(now - 30_000L, now))
        assertEquals("5m ago", freshnessLabel(now - 5 * 60_000L, now))
        assertEquals("3h ago", freshnessLabel(now - 3 * 60 * 60_000L, now))
        assertEquals("5d ago", freshnessLabel(now - 5 * day, now))
    }
}
