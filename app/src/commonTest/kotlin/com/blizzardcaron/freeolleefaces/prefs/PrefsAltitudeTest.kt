package com.blizzardcaron.freeolleefaces.prefs

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PrefsAltitudeTest {
    @Test fun altitude_fetch_is_cached_as_raw_metres() {
        val prefs = Prefs(MapSettings())
        assertNull(prefs.altitudeValueM)
        prefs.recordAltitudeFetch(1620.0)
        assertEquals(1620.0, prefs.altitudeValueM)
    }
}
