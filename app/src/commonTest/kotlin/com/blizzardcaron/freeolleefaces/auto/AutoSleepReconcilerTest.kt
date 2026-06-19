package com.blizzardcaron.freeolleefaces.auto

import com.blizzardcaron.freeolleefaces.prefs.AutoSleepProfile
import com.blizzardcaron.freeolleefaces.prefs.AutoSleepWindowConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AutoSleepReconcilerTest {

    private val inP = AutoSleepProfile(autoSleepOn = true, periodSec = 120)
    private val outP = AutoSleepProfile(autoSleepOn = false, periodSec = 120)

    private fun cfg(enabled: Boolean, start: Int, end: Int) =
        AutoSleepWindowConfig(enabled, start, end, inP, outP)

    @Test fun disabled_returnsNull() {
        assertNull(AutoSleepReconciler.desiredProfile(cfg(false, 22 * 60, 7 * 60), 23 * 60))
    }

    @Test fun insideMidnightCrossingWindow_returnsInProfile() {
        // window 22:00 -> 07:00; 23:00 and 02:00 are inside, 12:00 is outside
        val c = cfg(true, 22 * 60, 7 * 60)
        assertEquals(inP, AutoSleepReconciler.desiredProfile(c, 23 * 60))
        assertEquals(inP, AutoSleepReconciler.desiredProfile(c, 2 * 60))
        assertEquals(outP, AutoSleepReconciler.desiredProfile(c, 12 * 60))
    }

    @Test fun boundaries_startInclusive_endExclusive() {
        val c = cfg(true, 22 * 60, 7 * 60)
        assertEquals(inP, AutoSleepReconciler.desiredProfile(c, 22 * 60)) // start inclusive
        assertEquals(outP, AutoSleepReconciler.desiredProfile(c, 7 * 60)) // end exclusive
    }

    @Test fun sameDayWindow_returnsInProfileWithinRange() {
        val c = cfg(true, 9 * 60, 17 * 60)
        assertEquals(inP, AutoSleepReconciler.desiredProfile(c, 12 * 60))
        assertEquals(outP, AutoSleepReconciler.desiredProfile(c, 8 * 60))
    }
}
