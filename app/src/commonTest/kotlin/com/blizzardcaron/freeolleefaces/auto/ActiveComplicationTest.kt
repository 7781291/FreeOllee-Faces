package com.blizzardcaron.freeolleefaces.auto

import kotlin.test.Test
import kotlin.test.assertEquals

class ActiveComplicationTest {

    @Test fun legacyOffMapsToTemperature() {
        assertEquals(ActiveComplication.TEMPERATURE, ActiveComplication.fromLegacyAutoSource("OFF"))
    }

    @Test fun legacyNullMapsToTemperature() {
        assertEquals(ActiveComplication.TEMPERATURE, ActiveComplication.fromLegacyAutoSource(null))
    }

    @Test fun legacyTemperatureMapsToTemperature() {
        assertEquals(ActiveComplication.TEMPERATURE, ActiveComplication.fromLegacyAutoSource("TEMPERATURE"))
    }

    @Test fun legacySunMapsToSun() {
        assertEquals(ActiveComplication.SUN, ActiveComplication.fromLegacyAutoSource("SUN"))
    }

    @Test fun unknownStringMapsToTemperature() {
        assertEquals(ActiveComplication.TEMPERATURE, ActiveComplication.fromLegacyAutoSource("WAT"))
    }
}
