package com.blizzardcaron.freeolleefaces.timer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TimerModelsTest {

    @Test
    fun `blank creates exactly 10 empty slots`() {
        val set = TimerSet.blank("id1", "Morning")
        assertEquals(10, set.slots.size)
        assertEquals(List(10) { 0 }, set.durations())
        assertEquals("", set.slots[0].label)
    }

    @Test
    fun `durations maps slot order to seconds`() {
        val slots = (1..10).map { TimerSlot("L$it", it * 10) }
        val set = TimerSet("id", "name", slots)
        assertEquals((1..10).map { it * 10 }, set.durations())
    }

    @Test
    fun `constructing a set with the wrong slot count throws`() {
        assertFailsWith<IllegalArgumentException> {
            TimerSet("id", "name", listOf(TimerSlot()))
        }
    }
}
