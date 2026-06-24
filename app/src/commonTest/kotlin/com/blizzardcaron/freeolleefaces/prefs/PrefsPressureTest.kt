package com.blizzardcaron.freeolleefaces.prefs

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PrefsPressureTest {
    @Test fun pressure_fetch_is_cached_as_raw_hpa() {
        val prefs = Prefs(MapSettings())
        assertNull(prefs.pressureValueHpa)
        prefs.recordPressureFetch(1013.0)
        assertEquals(1013.0, prefs.pressureValueHpa)
    }
}