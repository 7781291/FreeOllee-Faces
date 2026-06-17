package com.blizzardcaron.freeolleefaces.timer

import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals

class QuickAlarmTest {

    @Test
    fun `target later today returns the forward delta`() {
        // 22:00 -> 23:00 = 1 h.
        assertEquals(3600, QuickAlarm.countdownSeconds(LocalTime(22, 0), 23, 0))
    }

    @Test
    fun `target earlier today rolls to the next day`() {
        // 22:00 -> 07:00 next day = 9 h.
        assertEquals(9 * 3600, QuickAlarm.countdownSeconds(LocalTime(22, 0), 7, 0))
    }

    @Test
    fun `target equal to now rolls a full day`() {
        assertEquals(86_400, QuickAlarm.countdownSeconds(LocalTime(22, 0), 22, 0))
    }

    @Test
    fun `accounts for the current seconds within the minute`() {
        // 22:00:30 -> 22:01:00 = 30 s.
        assertEquals(30, QuickAlarm.countdownSeconds(LocalTime(22, 0, 30), 22, 1))
    }

    @Test
    fun `wraps across midnight`() {
        // 23:59:00 -> 00:00 = 60 s.
        assertEquals(60, QuickAlarm.countdownSeconds(LocalTime(23, 59, 0), 0, 0))
    }
}
