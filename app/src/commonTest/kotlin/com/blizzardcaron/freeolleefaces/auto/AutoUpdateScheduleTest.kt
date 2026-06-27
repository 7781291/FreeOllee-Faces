package com.blizzardcaron.freeolleefaces.auto

import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoUpdateScheduleTest {

    // ----- isInSleepWindow: non-wrapping window 01:00–05:00 (60..300) -----

    @Test
    fun `non-wrap window contains a middle minute`() {
        assertTrue(AutoUpdateSchedule.isInSleepWindow(120, 60, 300))
    }

    @Test
    fun `non-wrap window excludes a minute before start`() {
        assertFalse(AutoUpdateSchedule.isInSleepWindow(30, 60, 300))
    }

    @Test
    fun `non-wrap window includes the start minute`() {
        assertTrue(AutoUpdateSchedule.isInSleepWindow(60, 60, 300))
    }

    @Test
    fun `non-wrap window excludes the end minute`() {
        assertFalse(AutoUpdateSchedule.isInSleepWindow(300, 60, 300))
    }

    // ----- isInSleepWindow: wrapping window 22:00–06:00 (1320..360) -----

    @Test
    fun `wrap window contains a late-evening minute`() {
        assertTrue(AutoUpdateSchedule.isInSleepWindow(1350, 1320, 360)) // 22:30
    }

    @Test
    fun `wrap window contains an early-morning minute`() {
        assertTrue(AutoUpdateSchedule.isInSleepWindow(30, 1320, 360)) // 00:30
    }

    @Test
    fun `wrap window includes the start minute`() {
        assertTrue(AutoUpdateSchedule.isInSleepWindow(1320, 1320, 360)) // 22:00
    }

    @Test
    fun `wrap window excludes the end minute`() {
        assertFalse(AutoUpdateSchedule.isInSleepWindow(360, 1320, 360)) // 06:00
    }

    @Test
    fun `wrap window excludes a midday minute`() {
        assertFalse(AutoUpdateSchedule.isInSleepWindow(720, 1320, 360)) // 12:00
    }

    @Test
    fun `start equals end means never in window`() {
        assertFalse(AutoUpdateSchedule.isInSleepWindow(100, 300, 300))
    }

    // ----- nextTemperatureFire -----

    private fun at(hour: Int, minute: Int): LocalDateTime =
        LocalDateTime(2026, 5, 25, hour, minute, 0)

    @Test
    fun `no sleep window returns now plus interval`() {
        val result = AutoUpdateSchedule.nextTemperatureFire(at(12, 0), 60, null)
        assertEquals(at(13, 0), result)
    }

    @Test
    fun `fire landing inside window snaps to end same day`() {
        // now 02:00, +60 = 03:00 which is inside 22:00–06:00 -> snap to 06:00 same day.
        val result = AutoUpdateSchedule.nextTemperatureFire(at(2, 0), 60, SleepWindow(1320, 360))
        assertEquals(at(6, 0), result)
    }

    @Test
    fun `fire landing inside window in the evening snaps to next-day end`() {
        // now 21:30, +60 = 22:30 inside window -> snap to 06:00 the NEXT day.
        val result = AutoUpdateSchedule.nextTemperatureFire(at(21, 30), 60, SleepWindow(1320, 360))
        assertEquals(LocalDateTime(2026, 5, 26, 6, 0, 0), result)
    }

    @Test
    fun `fire outside window is unchanged`() {
        // now 08:00, +60 = 09:00, not in window.
        val result = AutoUpdateSchedule.nextTemperatureFire(at(8, 0), 60, SleepWindow(1320, 360))
        assertEquals(at(9, 0), result)
    }

    // ----- backstop backoff + budget (Layer 2) -----

    @Test
    fun `backstop backoff is 2 then 5 then 15 minutes`() {
        assertEquals(2L * 60_000L, AutoUpdateSchedule.backstopDelayMs(0))
        assertEquals(5L * 60_000L, AutoUpdateSchedule.backstopDelayMs(1))
        assertEquals(15L * 60_000L, AutoUpdateSchedule.backstopDelayMs(2))
    }

    @Test
    fun `backstop backoff stays at 15 minutes past the schedule`() {
        assertEquals(15L * 60_000L, AutoUpdateSchedule.backstopDelayMs(3))
    }

    @Test
    fun `budget remains for the first three attempts and is then exhausted`() {
        assertTrue(AutoUpdateSchedule.hasBackstopBudget(0))
        assertTrue(AutoUpdateSchedule.hasBackstopBudget(1))
        assertTrue(AutoUpdateSchedule.hasBackstopBudget(2))
        assertFalse(AutoUpdateSchedule.hasBackstopBudget(3))
    }
}
