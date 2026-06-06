package com.blizzardcaron.freeolleefaces.prefs

import kotlin.test.Test
import kotlin.test.assertEquals

class IntervalOptionsTest {

    @Test fun presetsPassThrough() {
        assertEquals(15, IntervalOptions.coerce(15))
        assertEquals(30, IntervalOptions.coerce(30))
        assertEquals(45, IntervalOptions.coerce(45))
        assertEquals(60, IntervalOptions.coerce(60))
    }

    @Test fun nonPresetSnapsToDefault() {
        // legacy free-text values and anything off-list fall back to 15
        assertEquals(15, IntervalOptions.coerce(20))
        assertEquals(15, IntervalOptions.coerce(90))
        assertEquals(15, IntervalOptions.coerce(0))
        assertEquals(15, IntervalOptions.coerce(-5))
    }

    @Test fun defaultIsFifteen() {
        assertEquals(15, IntervalOptions.DEFAULT)
    }
}
