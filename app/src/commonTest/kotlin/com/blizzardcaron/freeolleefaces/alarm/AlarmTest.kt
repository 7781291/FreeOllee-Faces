package com.blizzardcaron.freeolleefaces.alarm

import kotlinx.datetime.DayOfWeek
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AlarmTest {
    @Test fun `repeatsOn maps bit0 to Monday and bit6 to Sunday`() {
        val monAndSun = Alarm.bit(DayOfWeek.MONDAY) or Alarm.bit(DayOfWeek.SUNDAY)
        val a = Alarm(id = "1", hour = 7, minute = 0, daysMask = monAndSun)
        assertTrue(a.repeatsOn(DayOfWeek.MONDAY))
        assertTrue(a.repeatsOn(DayOfWeek.SUNDAY))
        assertFalse(a.repeatsOn(DayOfWeek.TUESDAY))
    }

    @Test fun `ALL_DAYS repeats every weekday`() {
        val a = Alarm(id = "1", hour = 6, minute = 30, daysMask = Alarm.ALL_DAYS)
        DayOfWeek.entries.forEach { assertTrue(a.repeatsOn(it), "should repeat on $it") }
        assertEquals(0x7F, Alarm.ALL_DAYS)
        DayOfWeek.entries.forEach { assertFalse(Alarm(id = "z", hour = 0, minute = 0, daysMask = 0).repeatsOn(it)) }
    }

    @Test fun `rejects out-of-range fields`() {
        assertFailsWith<IllegalArgumentException> { Alarm(id = "1", hour = 24, minute = 0) }
        assertFailsWith<IllegalArgumentException> { Alarm(id = "1", hour = 0, minute = 60) }
        assertFailsWith<IllegalArgumentException> { Alarm(id = "1", hour = 0, minute = 0, daysMask = 0x80) }
        assertFailsWith<IllegalArgumentException> { Alarm(id = "1", hour = 0, minute = 0, chimeIndex = 14) }
    }
}
