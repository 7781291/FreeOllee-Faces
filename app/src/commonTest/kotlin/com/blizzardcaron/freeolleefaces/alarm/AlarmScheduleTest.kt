package com.blizzardcaron.freeolleefaces.alarm

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AlarmScheduleTest {

    // Wed 2026-06-10 10:00.
    private val now = LocalDateTime(2026, 6, 10, 10, 0)

    @Test fun `today counts only when the time is strictly in the future`() {
        val later = Alarm(id = "a", hour = 10, minute = 1)
        assertEquals(LocalDateTime(2026, 6, 10, 10, 1), AlarmSchedule.nextFire(listOf(later), now)?.dateTime)

        // 10:00 is not strictly after 10:00 -> rolls to tomorrow.
        val exactlyNow = Alarm(id = "b", hour = 10, minute = 0)
        assertEquals(LocalDateTime(2026, 6, 11, 10, 0), AlarmSchedule.nextFire(listOf(exactlyNow), now)?.dateTime)
    }

    @Test fun `skips days not in the mask`() {
        // Mon-only alarm checked on a Wednesday -> next Monday, 2026-06-15.
        val monday = Alarm(id = "a", hour = 7, minute = 0, daysMask = Alarm.bit(DayOfWeek.MONDAY))
        assertEquals(LocalDateTime(2026, 6, 15, 7, 0), AlarmSchedule.nextFire(listOf(monday), now)?.dateTime)
    }

    @Test fun `wraps a full week when today's only occurrence has passed`() {
        // Wed-only alarm at 09:00, checked Wed 10:00 -> next Wednesday, 2026-06-17.
        val wed = Alarm(id = "a", hour = 9, minute = 0, daysMask = Alarm.bit(DayOfWeek.WEDNESDAY))
        assertEquals(LocalDateTime(2026, 6, 17, 9, 0), AlarmSchedule.nextFire(listOf(wed), now)?.dateTime)
    }

    @Test fun `picks the soonest across alarms and carries that alarm's time and chime`() {
        val late = Alarm(id = "a", hour = 22, minute = 0, chimeIndex = 3)
        val soon = Alarm(id = "b", hour = 11, minute = 30, chimeIndex = 1)
        val next = AlarmSchedule.nextFire(listOf(late, soon), now)!!
        assertEquals(LocalDateTime(2026, 6, 10, 11, 30), next.dateTime)
        assertEquals(11, next.hour)
        assertEquals(30, next.minute)
        assertEquals(1, next.chimeIndex)
    }

    @Test fun `tie goes to the earliest-listed alarm`() {
        val first = Alarm(id = "a", hour = 11, minute = 0, chimeIndex = 2)
        val second = Alarm(id = "b", hour = 11, minute = 0, chimeIndex = 9)
        assertEquals(2, AlarmSchedule.nextFire(listOf(first, second), now)?.chimeIndex)
    }

    @Test fun `disabled and empty-days alarms contribute nothing`() {
        val disabled = Alarm(id = "a", hour = 11, minute = 0, enabled = false)
        val inert = Alarm(id = "b", hour = 11, minute = 0, daysMask = 0)
        assertNull(AlarmSchedule.nextFire(listOf(disabled, inert), now))
        assertNull(AlarmSchedule.nextFire(emptyList(), now))
    }
}
