package com.blizzardcaron.freeolleefaces.format

import kotlin.test.Test
import kotlin.test.assertEquals

class DisplayFormatterAltitudeTest {
    @Test fun metric_renders_metres_right_justified() {
        assertEquals(" 1620m", DisplayFormatter.altitude(1620.0, imperial = false))
    }

    @Test fun imperial_converts_to_feet() {
        // 1620 m -> 5315.0 ft
        assertEquals(" 5315f", DisplayFormatter.altitude(1620.0, imperial = true))
    }

    @Test fun stale_replaces_leading_pad_with_E() {
        assertEquals("E1620m", DisplayFormatter.altitude(1620.0, imperial = false, stale = true))
    }
}
