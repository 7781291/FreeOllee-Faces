package com.blizzardcaron.freeolleefaces.glyph

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Serializable
private data class GlyphFile(val glyphs: List<Entry>, val dpCells: List<Int> = emptyList())

@Serializable
private data class Entry(val ascii: Int, val char: String, val mask: String, val tier: String, val tensMask: String? = null)

/** Guards that NameplateGlyphs stays in sync with the ground-truth contract (glyph-map.json). */
class NameplateGlyphsTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun load(): GlyphFile {
        val text = this::class.java.classLoader!!.getResource("glyph-map.json")!!.readText()
        return json.decodeFromString(GlyphFile.serializer(), text)
    }

    @Test
    fun masks_match_the_contract() {
        load().glyphs.forEach { e ->
            val c = e.char.single()
            assertEquals(e.mask.removePrefix("0x").toInt(16), NameplateGlyphs.maskFor(c), "mask for '$c'")
        }
    }

    @Test
    fun tens_masks_match_the_contract() {
        load().glyphs.forEach { e ->
            val c = e.char.single()
            val expected = (e.tensMask ?: e.mask).removePrefix("0x").toInt(16)
            assertEquals(expected, NameplateGlyphs.maskFor(c, FontClass.TENS), "tens mask for '$c'")
        }
    }

    @Test
    fun legible_set_is_clear_plus_approximate() {
        val expected = load().glyphs
            .filter { it.tier == "clear" || it.tier == "approximate" }
            .map { it.char.single() }
            .toSet()
        assertEquals(expected, NameplateGlyphs.LEGIBLE)
    }

    @Test
    fun dp_cells_match_the_contract() {
        assertEquals(load().dpCells.toSet(), NameplateGlyphs.DP_CELLS)
    }

    @Test
    fun segments_decode_from_mask_bits() {
        // 'P' mask 0x73 = bits a,b,e,f,g
        assertEquals(
            setOf(Segment.A, Segment.B, Segment.E, Segment.F, Segment.G),
            NameplateGlyphs.segmentsFor('P'),
        )
    }

    @Test
    fun substitutions_target_legible_chars() {
        NameplateGlyphs.SUBSTITUTIONS.values.forEach {
            assertTrue(NameplateGlyphs.isLegible(it), "substitution target '$it' must be legible")
        }
    }

    @Test
    fun substitutions_only_remap_illegible_sources() {
        // A substitution only makes sense for a char that doesn't read on its own; remapping a
        // legible char would silently alter correct output.
        NameplateGlyphs.SUBSTITUTIONS.keys.forEach {
            assertTrue(!NameplateGlyphs.isLegible(it), "substitution source '$it' should be illegible")
        }
    }
}
