package com.blizzardcaron.freeolleefaces.glyph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NameplateSanitizerTest {
    @Test
    fun digits_pass_through_unchanged() {
        assertEquals("123456", NameplateSanitizer.sanitize("123456"))
    }

    @Test
    fun output_is_always_legible_or_blank() {
        val all = (0x20..0x7E).map { it.toChar() }.joinToString("")
        all.chunked(NameplateSanitizer.MAX_CELLS).forEach { chunk ->
            NameplateSanitizer.sanitize(chunk).forEach { c ->
                assertTrue(c == ' ' || NameplateGlyphs.isLegible(c), "sanitized '$c' not legible/blank")
            }
        }
    }

    @Test
    fun truncates_to_six_cells() {
        assertEquals(6, NameplateSanitizer.sanitize("1234567890").length)
    }

    @Test
    fun substitutes_unusable_i_with_legible_I() {
        assertEquals("I", NameplateSanitizer.sanitize("i"))
    }

    @Test
    fun blanks_unusable_with_no_substitute() {
        // ':' is unusable (renders blank) with no substitution -> becomes a space
        assertEquals("9 30", NameplateSanitizer.sanitize("9:30"))
    }

    @Test
    fun is_idempotent() {
        val s = NameplateSanitizer.sanitize("aB%9/:")
        assertEquals(s, NameplateSanitizer.sanitize(s))
    }
}
