package com.blizzardcaron.freeolleefaces.instruments

import kotlin.test.Test
import kotlin.test.assertEquals

class CardinalTest {
    @Test fun boundariesMapToExpectedSectors() {
        assertEquals("N ", cardinal8(0f))
        assertEquals("NE", cardinal8(22.5f))
        assertEquals("NE", cardinal8(45f))
        assertEquals("E ", cardinal8(67.5f))
        assertEquals("E ", cardinal8(90f))
        assertEquals("S ", cardinal8(180f))
        assertEquals("W ", cardinal8(270f))
        assertEquals("NW", cardinal8(315f))
        assertEquals("N ", cardinal8(337.5f))
    }

    @Test fun wrapsAndNormalizes() {
        assertEquals("N ", cardinal8(360f))
        assertEquals("N ", cardinal8(720f))
        assertEquals("W ", cardinal8(-90f))
    }
}
