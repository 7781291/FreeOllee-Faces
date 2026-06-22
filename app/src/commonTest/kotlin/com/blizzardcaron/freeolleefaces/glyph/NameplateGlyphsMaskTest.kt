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

    @Test
    fun `tens font renders 7 as the c+g stub`() {
        assertEquals(0x44, NameplateGlyphs.maskFor('7', FontClass.TENS))
        assertEquals(0x07, NameplateGlyphs.maskFor('7', FontClass.UNITS))
        assertEquals(0x07, NameplateGlyphs.maskFor('7')) // default = UNITS
    }

    @Test
    fun `tens font overrides a sample of letters`() {
        assertEquals(0x5F, NameplateGlyphs.maskFor('A', FontClass.TENS))
        assertEquals(0x3F, NameplateGlyphs.maskFor('o', FontClass.TENS))
        assertEquals(0x62, NameplateGlyphs.maskFor('U', FontClass.TENS))
    }

    @Test
    fun `chars absent from the tens font fall back to the canonical mask`() {
        // '5' and '#' render identically in both classes
        assertEquals(NameplateGlyphs.maskFor('5'), NameplateGlyphs.maskFor('5', FontClass.TENS))
        assertEquals(NameplateGlyphs.maskFor('#'), NameplateGlyphs.maskFor('#', FontClass.TENS))
    }

    @Test
    fun `segmentsFor honors the font class`() {
        assertEquals(setOf(Segment.C, Segment.G), NameplateGlyphs.segmentsFor('7', FontClass.TENS))
        assertEquals(setOf(Segment.A, Segment.B, Segment.C), NameplateGlyphs.segmentsFor('7'))
    }
}
