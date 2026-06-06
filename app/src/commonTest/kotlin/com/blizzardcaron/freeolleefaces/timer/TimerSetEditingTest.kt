package com.blizzardcaron.freeolleefaces.timer

import kotlin.test.Test
import kotlin.test.assertEquals

class TimerSetEditingTest {

    private fun slots() = (0 until 10).map { TimerSlot("L$it", it) }

    @Test
    fun `fillDown copies the source duration into every slot below, leaving labels`() {
        val result = TimerSetEditing.fillDown(slots(), fromIndex = 1)
        assertEquals(1, result[1].durationSeconds)        // source unchanged
        assertEquals(0, result[0].durationSeconds)        // above untouched
        assertEquals(List(8) { 1 }, result.drop(2).map { it.durationSeconds }) // below = source
        assertEquals("L5", result[5].label)               // labels preserved
        assertEquals(10, result.size)
    }

    @Test
    fun `fillDown from the last index is a no-op on durations`() {
        val result = TimerSetEditing.fillDown(slots(), fromIndex = 9)
        assertEquals(slots().map { it.durationSeconds }, result.map { it.durationSeconds })
    }

    @Test
    fun `duplicateToNext copies label and duration into the next slot`() {
        val result = TimerSetEditing.duplicateToNext(slots(), index = 3)
        assertEquals(slots()[3], result[4])
        assertEquals(slots()[2], result[2]) // others untouched
        assertEquals(10, result.size)
    }

    @Test
    fun `duplicateToNext at the last index is a no-op`() {
        assertEquals(slots(), TimerSetEditing.duplicateToNext(slots(), index = 9))
    }

    @Test
    fun `hms and seconds round-trip`() {
        assertEquals(83, TimerSetEditing.hmsToSeconds(0, 1, 23))
        assertEquals(Triple(1, 0, 0), TimerSetEditing.secondsToHms(3600))
        assertEquals(Triple(0, 1, 23), TimerSetEditing.secondsToHms(83))
        assertEquals("00:10:00", TimerSetEditing.formatHms(600))
    }
}
