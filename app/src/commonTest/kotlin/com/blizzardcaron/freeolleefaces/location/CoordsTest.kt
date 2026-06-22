package com.blizzardcaron.freeolleefaces.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CoordsTest {
    @Test fun newMotionFieldsDefaultNull() {
        val c = Coords(lat = 1.0, lng = 2.0, accuracyM = 3f, provider = "gps")
        assertNull(c.bearingDeg)
        assertNull(c.speedMps)
    }

    @Test fun motionFieldsRoundTrip() {
        val c = Coords(1.0, 2.0, 3f, "gps", altM = 100.0, bearingDeg = 270f, speedMps = 1.5f)
        assertEquals(270f, c.bearingDeg)
        assertEquals(1.5f, c.speedMps)
    }
}
