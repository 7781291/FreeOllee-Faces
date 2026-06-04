package com.blizzardcaron.freeolleefaces.timer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerSetsJsonTest {

    private fun sampleSet(id: String) = TimerSet(
        id, "HIIT",
        (0 until 10).map { TimerSlot(if (it == 0) "Sprint" else "", it * 5) },
    )

    @Test
    fun `encode then decode round-trips sets, labels and durations`() {
        val sets = listOf(sampleSet("a"), TimerSet.blank("b", "Rest day"))
        val decoded = TimerSetsJson.decode(TimerSetsJson.encode(sets))
        assertEquals(sets, decoded)
    }

    @Test
    fun `decode of null or blank yields an empty list`() {
        assertTrue(TimerSetsJson.decode(null).isEmpty())
        assertTrue(TimerSetsJson.decode("").isEmpty())
        assertTrue(TimerSetsJson.decode("   ").isEmpty())
    }

    @Test
    fun `decode of malformed json yields an empty list, never throws`() {
        assertTrue(TimerSetsJson.decode("{not json").isEmpty())
        assertTrue(TimerSetsJson.decode("42").isEmpty())
    }

    @Test
    fun `decode skips a set whose slot count is not ten`() {
        // One valid set, one with only 2 slots -> only the valid one survives.
        val json = """[
          {"id":"ok","name":"n","slots":${"[" + (0 until 10).joinToString(",") { "{\"label\":\"\",\"dur\":0}" } + "]"}},
          {"id":"bad","name":"n","slots":[{"label":"","dur":0},{"label":"","dur":0}]}
        ]"""
        val decoded = TimerSetsJson.decode(json)
        assertEquals(1, decoded.size)
        assertEquals("ok", decoded[0].id)
    }
}
