package com.blizzardcaron.freeolleefaces.auto

import kotlin.test.Test
import kotlin.test.assertEquals

class ComplicationLabelTest {
    @Test fun labels_each_complication() {
        assertEquals("Temperature", ActiveComplication.TEMPERATURE.displayLabel())
        assertEquals("Sun event", ActiveComplication.SUN.displayLabel())
        assertEquals("Steps", ActiveComplication.STEPS.displayLabel())
        assertEquals("Custom", ActiveComplication.CUSTOM.displayLabel())
    }
}
