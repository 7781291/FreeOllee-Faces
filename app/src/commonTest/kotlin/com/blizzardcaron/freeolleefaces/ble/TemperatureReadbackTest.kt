package com.blizzardcaron.freeolleefaces.ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TemperatureReadbackTest {
    @Test fun parsesPaddedFahrenheit() {
        assertEquals(54, TemperatureReadback.parseTempF("  54 F".encodeToByteArray()))
    }
    @Test fun parsesNegative() {
        assertEquals(-7, TemperatureReadback.parseTempF(" -7 F".encodeToByteArray()))
    }
    @Test fun malformedIsNull() {
        assertNull(TemperatureReadback.parseTempF("   F".encodeToByteArray()))
        assertNull(TemperatureReadback.parseTempF(ByteArray(0)))
    }
}
