package com.blizzardcaron.freeolleefaces.format

import kotlin.test.Test
import kotlin.test.assertEquals

class NumberFormatTest {
    @Test fun decimals_round() {
        assertEquals("72.0", formatDecimal(72.04, 1))
        assertEquals("72.1", formatDecimal(72.05, 1))
        assertEquals("-3.5", formatDecimal(-3.45, 1))
        assertEquals("0.0", formatDecimal(0.0, 1))
    }
    @Test fun groups_thousands() {
        assertEquals("1,234", groupThousands(1234))
        assertEquals("999", groupThousands(999))
        assertEquals("1,000,000", groupThousands(1_000_000))
    }
}
