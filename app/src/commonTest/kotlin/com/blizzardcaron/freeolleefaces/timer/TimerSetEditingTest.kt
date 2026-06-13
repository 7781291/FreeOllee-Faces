package com.blizzardcaron.freeolleefaces.timer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    @Test
    fun `sortByTime orders non-blank slots ascending and keeps labels with durations`() {
        val input = listOf(
            TimerSlot("a", 90), TimerSlot("b", 30), TimerSlot("c", 60),
        ) + List(7) { TimerSlot("z$it", 0) }
        val result = TimerSetEditing.sortByTime(input)
        assertEquals(listOf(30, 60, 90), result.take(3).map { it.durationSeconds })
        assertEquals(listOf("b", "c", "a"), result.take(3).map { it.label }) // labels travel
        assertEquals(10, result.size)
    }

    @Test
    fun `sortByTime pushes blank slots to the bottom`() {
        val input = listOf(
            TimerSlot("blank1", 0), TimerSlot("real", 45), TimerSlot("blank2", 0),
        ) + List(7) { TimerSlot("z$it", 0) }
        val result = TimerSetEditing.sortByTime(input)
        assertEquals(45, result[0].durationSeconds)                 // only non-blank first
        assertTrue(result.drop(1).all { it.durationSeconds == 0 })  // everything else blank
    }

    @Test
    fun `sortByTime is stable for equal durations`() {
        val input = listOf(
            TimerSlot("first", 60), TimerSlot("second", 60), TimerSlot("third", 60),
        ) + List(7) { TimerSlot("", 0) }
        val result = TimerSetEditing.sortByTime(input)
        assertEquals(listOf("first", "second", "third"), result.take(3).map { it.label })
    }

    @Test
    fun `sortByTime keeps blank slots in their original relative order`() {
        val input = listOf(
            TimerSlot("real", 60), TimerSlot("blankA", 0), TimerSlot("blankB", 0),
        ) + (0 until 7).map { TimerSlot("pad$it", 0) }
        val result = TimerSetEditing.sortByTime(input)
        assertEquals("blankA", result[1].label) // first blank stays ahead of second
        assertEquals("blankB", result[2].label)
    }

    @Test
    fun `sortByTime on all-blank input is unchanged`() {
        val input = (0 until 10).map { TimerSlot("L$it", 0) }
        assertEquals(input, TimerSetEditing.sortByTime(input))
    }

    @Test
    fun `sortByTime does not mutate the input list`() {
        val input = listOf(TimerSlot("a", 90), TimerSlot("b", 30)) + List(8) { TimerSlot("", 0) }
        val snapshot = input.toList()
        TimerSetEditing.sortByTime(input)
        assertEquals(snapshot, input)
    }
}
