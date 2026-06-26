package com.blizzardcaron.freeolleefaces.format

import kotlin.test.Test
import kotlin.test.assertEquals

class DisplayFormatterPressureTest {
    @Test fun metric_renders_hpa_integer_right_justified() {
        assertEquals("  1013", DisplayFormatter.pressure(1013.2, imperial = false))
    }

    @Test fun imperial_renders_inhg_two_decimals() {
        assertEquals(" 29.91", DisplayFormatter.pressure(1013.0, imperial = true))
    }

    @Test fun stale_replaces_leading_pad_with_E() {
        assertEquals("E 1013", DisplayFormatter.pressure(1013.2, imperial = false, stale = true))
    }
}
