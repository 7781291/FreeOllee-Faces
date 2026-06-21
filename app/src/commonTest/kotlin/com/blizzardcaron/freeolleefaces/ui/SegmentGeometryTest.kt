package com.blizzardcaron.freeolleefaces.ui

import com.blizzardcaron.freeolleefaces.glyph.Segment
import kotlin.test.Test
import kotlin.test.assertEquals

class SegmentGeometryTest {
    @Test
    fun `maps each cell to its glyph segments left aligned blank padded`() {
        val cells = litSegments("P1", cellCount = 3)
        assertEquals(3, cells.size)
        assertEquals(setOf(Segment.A, Segment.B, Segment.E, Segment.F, Segment.G), cells[0]) // P 0x73
        assertEquals(setOf(Segment.B, Segment.C), cells[1]) // 1 0x06
        assertEquals(emptySet(), cells[2]) // blank pad
    }
}
