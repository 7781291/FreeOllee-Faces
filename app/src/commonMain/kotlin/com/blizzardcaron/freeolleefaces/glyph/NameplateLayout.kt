package com.blizzardcaron.freeolleefaces.glyph

/** Physical size band of a nameplate cell. */
enum class CellSize { LARGE, MEDIUM }

/** One of the 6 nameplate cells: its size band and which font it renders. */
data class NameplateCell(val size: CellSize, val font: FontClass)

/**
 * The watch nameplate surface (hw display positions 4-9), verified against the live official-app
 * preview 2026-06-21. Two independent per-cell attributes (size band, font class) plus right-
 * alignment and an HH:MM colon gap. Cells 0/2 (hours/minutes tens) use the TENS font; cells 4/5
 * (seconds) are medium-size. Single source of truth for litSegments + SegmentReadout.
 */
object NameplateLayout {
    val CELLS: List<NameplateCell> = listOf(
        NameplateCell(CellSize.LARGE, FontClass.TENS), // 0 pos4 hours-tens
        NameplateCell(CellSize.LARGE, FontClass.UNITS), // 1 pos5 hours-units
        NameplateCell(CellSize.LARGE, FontClass.TENS), // 2 pos6 minutes-tens
        NameplateCell(CellSize.LARGE, FontClass.UNITS), // 3 pos7 minutes-units
        NameplateCell(CellSize.MEDIUM, FontClass.UNITS), // 4 pos8 seconds-tens
        NameplateCell(CellSize.MEDIUM, FontClass.UNITS), // 5 pos9 seconds-units
    )
    const val COUNT = 6

    /** Medium cell scale vs a large cell (measured h 86/103≈0.835, w 37/47≈0.79). */
    const val MEDIUM_SCALE = 0.8f

    /** Extra advance inserted after this cell index (the HH:MM colon space). */
    const val COLON_GAP_AFTER_INDEX = 1

    /** Colon gap width as a fraction of a large cell width (measured ≈0.4). */
    const val COLON_EXTRA_GAP_FRACTION = 0.4f
}
