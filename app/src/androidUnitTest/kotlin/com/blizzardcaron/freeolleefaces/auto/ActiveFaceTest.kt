package com.blizzardcaron.freeolleefaces.auto

import org.junit.Assert.assertEquals
import org.junit.Test

class ActiveFaceTest {

    @Test fun legacyOffMapsToTemperature() {
        assertEquals(ActiveFace.TEMPERATURE, ActiveFace.fromLegacyAutoSource("OFF"))
    }

    @Test fun legacyNullMapsToTemperature() {
        assertEquals(ActiveFace.TEMPERATURE, ActiveFace.fromLegacyAutoSource(null))
    }

    @Test fun legacyTemperatureMapsToTemperature() {
        assertEquals(ActiveFace.TEMPERATURE, ActiveFace.fromLegacyAutoSource("TEMPERATURE"))
    }

    @Test fun legacySunMapsToSun() {
        assertEquals(ActiveFace.SUN, ActiveFace.fromLegacyAutoSource("SUN"))
    }

    @Test fun unknownStringMapsToTemperature() {
        assertEquals(ActiveFace.TEMPERATURE, ActiveFace.fromLegacyAutoSource("WAT"))
    }
}
