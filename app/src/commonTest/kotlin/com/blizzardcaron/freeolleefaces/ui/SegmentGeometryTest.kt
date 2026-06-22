package com.blizzardcaron.freeolleefaces.ui

import com.blizzardcaron.freeolleefaces.glyph.Segment
import kotlin.test.Test
import kotlin.test.assertEquals

class SegmentGeometryTest {
    @Test
    fun `right-aligns a short value, blanking the leading cells`() {
        val cells = litSegments("P1")
        assertEquals(6, cells.size)
        assertEquals(List(4) { emptySet() }, cells.subList(0, 4))
        assertEquals(setOf(Segment.A, Segment.B, Segment.E, Segment.F, Segment.G), cells[4]) // P 0x73
        assertEquals(setOf(Segment.B, Segment.C), cells[5]) // 1 0x06
    }

    @Test
    fun `picks the tens font for the tens cells`() {
        // "75#F" right-aligns to cells [_,_,7,5,#,F]; the 7 lands in cell 2 (TENS) -> c+g stub
        val cells = litSegments("75#F")
        assertEquals(emptySet(), cells[0])
        assertEquals(emptySet(), cells[1])
        assertEquals(setOf(Segment.C, Segment.G), cells[2])                         // 7 TENS 0x44
        assertEquals(setOf(Segment.A, Segment.C, Segment.D, Segment.F, Segment.G), cells[3]) // 5 0x6D
        assertEquals(setOf(Segment.A, Segment.B, Segment.F, Segment.G), cells[4])   // # 0x63
        assertEquals(setOf(Segment.A, Segment.E, Segment.F, Segment.G), cells[5])   // F 0x71
    }

    @Test
    fun `medium cells are scaled and baseline-aligned`() {
        val rects = nameplateCellRects(100f)
        assertEquals(6, rects.size)
        rects.subList(0, 4).forEach {
            assertEquals(100f, it.h, 0.01f); assertEquals(0f, it.y, 0.01f)
        }
        val mediumH = com.blizzardcaron.freeolleefaces.glyph.NameplateLayout.MEDIUM_SCALE * rects[0].h
        assertEquals(mediumH, rects[4].h, 0.01f)
        assertEquals(rects[0].h - mediumH, rects[4].y, 0.01f) // bottom-aligned to the large baseline
        assertEquals(com.blizzardcaron.freeolleefaces.glyph.NameplateLayout.MEDIUM_SCALE * rects[0].w, rects[4].w, 0.01f)
    }

    @Test
    fun `colon gap adds extra advance after cell 1`() {
        val rects = nameplateCellRects(100f)
        val normalGap = rects[1].x - (rects[0].x + rects[0].w)
        val colonGap = rects[2].x - (rects[1].x + rects[1].w)
        val extra = com.blizzardcaron.freeolleefaces.glyph.NameplateLayout.COLON_EXTRA_GAP_FRACTION * rects[0].w
        assertEquals(extra, colonGap - normalGap, 0.01f)
    }

    @Test
    fun `readout width spans all cells without trailing gap`() {
        val rects = nameplateCellRects(100f)
        assertEquals(rects.last().x + rects.last().w, nameplateReadoutWidth(100f), 0.01f)
    }
}
