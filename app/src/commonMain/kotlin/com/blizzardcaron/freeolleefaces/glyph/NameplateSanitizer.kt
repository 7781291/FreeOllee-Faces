package com.blizzardcaron.freeolleefaces.glyph

/** Last-line guard so the watch never shows garbage: keep legible chars, else substitute, else blank. */
object NameplateSanitizer {
    const val MAX_CELLS = 6

    fun sanitize(raw: String): String = buildString(MAX_CELLS) {
        for (c in raw.take(MAX_CELLS)) {
            append(
                when {
                    NameplateGlyphs.isLegible(c) -> c
                    c in NameplateGlyphs.SUBSTITUTIONS -> NameplateGlyphs.SUBSTITUTIONS.getValue(c)
                    else -> ' '
                },
            )
        }
    }
}
