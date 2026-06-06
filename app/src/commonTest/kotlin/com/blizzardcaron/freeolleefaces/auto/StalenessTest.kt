package com.blizzardcaron.freeolleefaces.auto

import com.blizzardcaron.freeolleefaces.format.TempUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StalenessTest {

    private val now = 1_000_000_000_000L

    @Test fun freshWhenWithinInterval() {
        assertTrue(
            isTempCacheFresh(
                fetchedMs = now - 10 * 60_000L,
                cacheUnit = TempUnit.FAHRENHEIT,
                currentUnit = TempUnit.FAHRENHEIT,
                intervalMin = 60,
                nowMs = now,
            )
        )
    }

    @Test fun staleWhenPastInterval() {
        assertFalse(
            isTempCacheFresh(now - 61 * 60_000L, TempUnit.FAHRENHEIT, TempUnit.FAHRENHEIT, 60, now)
        )
    }

    @Test fun staleWhenUnitMismatch() {
        assertFalse(
            isTempCacheFresh(now - 1 * 60_000L, TempUnit.CELSIUS, TempUnit.FAHRENHEIT, 60, now)
        )
    }

    @Test fun staleWhenNeverFetched() {
        assertFalse(isTempCacheFresh(null, null, TempUnit.FAHRENHEIT, 60, now))
    }
}
