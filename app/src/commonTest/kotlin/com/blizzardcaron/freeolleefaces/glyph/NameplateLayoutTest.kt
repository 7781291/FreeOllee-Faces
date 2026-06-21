package com.blizzardcaron.freeolleefaces.glyph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NameplateLayoutTest {
    @Test
    fun `six cells with the verified size bands`() {
        assertEquals(6, NameplateLayout.COUNT)
        assertEquals(6, NameplateLayout.CELLS.size)
        assertEquals(
            listOf(CellSize.LARGE, CellSize.LARGE, CellSize.LARGE, CellSize.LARGE, CellSize.MEDIUM, CellSize.MEDIUM),
            NameplateLayout.CELLS.map { it.size },
        )
    }

    @Test
    fun `tens font at cells 0 and 2, units elsewhere`() {
        assertEquals(
            listOf(FontClass.TENS, FontClass.UNITS, FontClass.TENS, FontClass.UNITS, FontClass.UNITS, FontClass.UNITS),
            NameplateLayout.CELLS.map { it.font },
        )
    }

    @Test
    fun `colon gap sits after the second main cell`() {
        assertEquals(1, NameplateLayout.COLON_GAP_AFTER_INDEX)
        assertTrue(NameplateLayout.COLON_EXTRA_GAP_FRACTION > 0f)
        assertTrue(NameplateLayout.MEDIUM_SCALE in 0.5f..1.0f)
    }
}
