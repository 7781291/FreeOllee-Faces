package com.blizzardcaron.freeolleefaces.glyph

import kotlin.test.Test
import kotlin.test.assertEquals

class NameplateGlyphsMaskTest {

    @Test
    fun `maskFor returns the firmware mask for known chars`() {
        assertEquals(0x73, NameplateGlyphs.maskFor('P'))
        assertEquals(0x00, NameplateGlyphs.maskFor(' '))
        assertEquals(0x7F, NameplateGlyphs.maskFor('8'))
    }

    @Test
    fun `maskFor returns 0 for an unknown char`() {
        assertEquals(0x00, NameplateGlyphs.maskFor('\n'))
    }

    @Test
    fun `segmentsFor decodes the mask bits into the Segment set`() {
        assertEquals(
            setOf(Segment.A, Segment.B, Segment.E, Segment.F, Segment.G),
            NameplateGlyphs.segmentsFor('P'),
        )
        assertEquals(
            setOf(Segment.B, Segment.C),
            NameplateGlyphs.segmentsFor('1'),
        )
    }
}
