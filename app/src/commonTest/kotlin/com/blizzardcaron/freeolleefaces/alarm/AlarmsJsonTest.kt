package com.blizzardcaron.freeolleefaces.alarm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AlarmsJsonTest {
    @Test fun `round-trips a list of alarms`() {
        val alarms = listOf(
            Alarm(id = "a", hour = 7, minute = 5, enabled = true, daysMask = 0x1F, chimeIndex = 1, label = "Work"),
            Alarm(id = "b", hour = 9, minute = 0, enabled = false, daysMask = 0x60, chimeIndex = 0, label = ""),
        )
        val decoded = AlarmsJson.decode(AlarmsJson.encode(alarms))
        assertEquals(alarms, decoded)
    }

    @Test fun `decode of null or garbage yields empty list`() {
        assertTrue(AlarmsJson.decode(null).isEmpty())
        assertTrue(AlarmsJson.decode("not json").isEmpty())
        assertTrue(AlarmsJson.decode("{}").isEmpty())
    }

    @Test fun `decode skips entries with out-of-range fields`() {
        // hour 99 is invalid -> that entry dropped, the valid one kept.
        val json = """[{"id":"x","hour":99,"minute":0,"enabled":true,"daysMask":127,"chime":0,"label":""},
                       {"id":"y","hour":8,"minute":0,"enabled":true,"daysMask":127,"chime":0,"label":""}]"""
        val decoded = AlarmsJson.decode(json)
        assertEquals(1, decoded.size)
        assertEquals("y", decoded[0].id)
    }

    @Test fun `missing enabled key defaults to true`() {
        val json = """[{"id":"x","hour":8,"minute":0,"daysMask":127,"chime":0,"label":""}]"""
        val decoded = AlarmsJson.decode(json)
        assertEquals(1, decoded.size)
        assertTrue(decoded[0].enabled)
    }
}
