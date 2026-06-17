package com.blizzardcaron.freeolleefaces.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class HourMathTest {

    @Test
    fun `hour24 maps 12-hour plus AM PM to the 0 to 23 range`() {
        assertEquals(0, hour24(12, pm = false))   // 12 AM = midnight
        assertEquals(12, hour24(12, pm = true))   // 12 PM = noon
        assertEquals(7, hour24(7, pm = false))    // 7 AM
        assertEquals(19, hour24(7, pm = true))    // 7 PM
    }

    @Test
    fun `hour12Of maps the 0 to 23 range to a 12-hour dial`() {
        assertEquals(12, hour12Of(0))   // midnight shows 12
        assertEquals(12, hour12Of(12))  // noon shows 12
        assertEquals(7, hour12Of(7))
        assertEquals(7, hour12Of(19))
    }

    @Test
    fun `isPm reflects the 24-hour value`() {
        assertEquals(false, isPm(0))
        assertEquals(false, isPm(11))
        assertEquals(true, isPm(12))
        assertEquals(true, isPm(23))
    }
}
