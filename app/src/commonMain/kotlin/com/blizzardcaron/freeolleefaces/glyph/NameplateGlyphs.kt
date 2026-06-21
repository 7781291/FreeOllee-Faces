package com.blizzardcaron.freeolleefaces.glyph

enum class Segment { A, B, C, D, E, F, G, DP }

/** Firmware segment-font for the watch nameplate. Source of truth: glyph-map.json. */
object NameplateGlyphs {
    private val BIT_TO_SEGMENT = listOf(
        Segment.A,
        Segment.B,
        Segment.C,
        Segment.D,
        Segment.E,
        Segment.F,
        Segment.G,
        Segment.DP,
    )

    // bit0=A..bit6=G, bit7=DP. Transcribed from glyph-map.json (every printable ASCII 0x20–0x7E).
    private val MASKS: Map<Char, Int> = mapOf(
        ' ' to 0x00, '!' to 0x60, '"' to 0x22, '#' to 0x63, '$' to 0x2D, '%' to 0x00,
        '&' to 0x44, '\'' to 0x20, '(' to 0x39, ')' to 0x0F, '*' to 0xC0, '+' to 0x70,
        ',' to 0x04, '-' to 0x40, '.' to 0x40, '/' to 0x12,
        '0' to 0x3F, '1' to 0x06, '2' to 0x5B, '3' to 0x4F, '4' to 0x66, '5' to 0x6D,
        '6' to 0x7D, '7' to 0x07, '8' to 0x7F, '9' to 0x6F,
        ':' to 0x00, ';' to 0x00, '<' to 0x58, '=' to 0x48, '>' to 0x4C, '?' to 0x53, '@' to 0xFF,
        'A' to 0x77, 'B' to 0x7F, 'C' to 0x39, 'D' to 0x3F, 'E' to 0x79, 'F' to 0x71, 'G' to 0x3D,
        'H' to 0x76, 'I' to 0x89, 'J' to 0x1E, 'K' to 0x75, 'L' to 0x38, 'M' to 0xB7, 'N' to 0x37,
        'O' to 0x3F, 'P' to 0x73, 'Q' to 0x67, 'R' to 0xF7, 'S' to 0x6D, 'T' to 0x81, 'U' to 0x3E,
        'V' to 0x3E, 'W' to 0xBE, 'X' to 0x7E, 'Y' to 0x6E, 'Z' to 0x1B,
        '[' to 0x39, '\\' to 0x24, ']' to 0x0F, '^' to 0x23, '_' to 0x08, '`' to 0x02,
        'a' to 0x5F, 'b' to 0x7C, 'c' to 0x58, 'd' to 0x5E, 'e' to 0x7B, 'f' to 0x71, 'g' to 0x6F,
        'h' to 0x74, 'i' to 0x10, 'j' to 0x42, 'k' to 0x75, 'l' to 0x30, 'm' to 0xB7, 'n' to 0x54,
        'o' to 0x5C, 'p' to 0x73, 'q' to 0x67, 'r' to 0x50, 's' to 0x6D, 't' to 0x78, 'u' to 0x62,
        'v' to 0x1C, 'w' to 0xBE, 'x' to 0x7E, 'y' to 0x6E, 'z' to 0x1B,
        '{' to 0x16, '|' to 0x36, '}' to 0x34, '~' to 0x01,
    )

    fun maskFor(c: Char): Int = MASKS[c] ?: 0x00

    fun segmentsFor(c: Char): Set<Segment> {
        val mask = maskFor(c)
        return BIT_TO_SEGMENT.filterIndexed { i, _ -> (mask shr i) and 1 == 1 }.toSet()
    }
}
