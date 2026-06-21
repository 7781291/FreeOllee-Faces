package com.blizzardcaron.freeolleefaces.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.blizzardcaron.freeolleefaces.glyph.NameplateGlyphs
import com.blizzardcaron.freeolleefaces.glyph.Segment
import com.blizzardcaron.freeolleefaces.ui.theme.BrandColors

/** Cell-by-cell lit segments for [value], left-aligned and blank-padded to [cellCount]. Pure. */
fun litSegments(value: String, cellCount: Int): List<Set<Segment>> =
    (0 until cellCount).map { i ->
        value.getOrNull(i)?.let { NameplateGlyphs.segmentsFor(it) } ?: emptySet()
    }

private const val CELLS = 6
private const val CELL_ASPECT = 0.6f // width / height of one cell
private const val CELL_GAP_FRACTION = 0.25f // gap between cells, relative to cell width

private const val SCREEN_CORNER_RADIUS_DP = 14
private const val SCREEN_PADDING_HORIZONTAL_DP = 16
private const val SCREEN_PADDING_VERTICAL_DP = 10

// Segment geometry, expressed as fractions of the cell's (width, height) so it scales with
// cellHeight. Horizontal bars (a/g/d) run across the cell; vertical bars (f/b upper, e/c lower)
// run along the left/right edges of the upper/lower half.
private const val BAR_THICKNESS_FRACTION = 0.16f // segment thickness relative to cell height
private const val BAR_CORNER_FRACTION = 0.4f // rounded-bar corner radius relative to thickness
private const val H_BAR_INSET_FRACTION = 0.12f // horizontal bar inset from the cell's side edges
private const val V_BAR_INSET_FRACTION = 0.08f // vertical bar inset from the cell's top/bottom/middle
private const val MIDLINE_FRACTION = 0.5f // vertical position of the middle (g) bar / half split

/** Faithful 7-segment readout: draws exactly the firmware segments, off segments as faint ghosts. */
@Composable
fun SegmentReadout(
    value: String,
    modifier: Modifier = Modifier,
    cellHeight: Dp = 40.dp,
    tone: LcdTone = LcdTone.Green,
) {
    val lit = if (tone == LcdTone.Aqua) BrandColors.LcdOnAqua else BrandColors.LcdOn
    val off = BrandColors.LcdOff
    val cells = litSegments(value, CELLS)
    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(SCREEN_CORNER_RADIUS_DP.dp))
            .background(BrandColors.LcdScreen)
            .padding(horizontal = SCREEN_PADDING_HORIZONTAL_DP.dp, vertical = SCREEN_PADDING_VERTICAL_DP.dp)
            .height(cellHeight),
    ) {
        val h = size.height
        val cw = h * CELL_ASPECT
        val stride = cw * (1f + CELL_GAP_FRACTION)
        cells.forEachIndexed { i, segs ->
            drawCell(Offset(i * stride, 0f), Size(cw, h), segs, lit, off)
        }
    }
}

/** Bar geometry shared by every segment of one cell, derived once from [origin]/[size]. */
private class CellGeometry(val origin: Offset, val size: Size) {
    val thickness = size.height * BAR_THICKNESS_FRACTION
    val corner = CornerRadius(thickness * BAR_CORNER_FRACTION)
    val hInset = size.width * H_BAR_INSET_FRACTION
    val vInset = size.height * V_BAR_INSET_FRACTION
    val midY = size.height * MIDLINE_FRACTION
}

/** Draws one 7-segment cell at [origin] with [size]; lit segments use [lit], absent use [off]. */
private fun DrawScope.drawCell(
    origin: Offset,
    size: Size,
    segments: Set<Segment>,
    lit: Color,
    off: Color,
) {
    val geo = CellGeometry(origin, size)
    fun colorOf(segment: Segment) = colorFor(segment, segments, lit, off)

    drawHorizontalBar(geo, topY = 0f, color = colorOf(Segment.A))
    drawHorizontalBar(geo, topY = geo.midY - geo.thickness / 2f, color = colorOf(Segment.G))
    drawHorizontalBar(geo, topY = size.height - geo.thickness, color = colorOf(Segment.D))

    drawVerticalBar(geo, left = true, upper = true, color = colorOf(Segment.F))
    drawVerticalBar(geo, left = false, upper = true, color = colorOf(Segment.B))
    drawVerticalBar(geo, left = true, upper = false, color = colorOf(Segment.E))
    drawVerticalBar(geo, left = false, upper = false, color = colorOf(Segment.C))
    // No dp/8th segment: this watch never renders it (NameplateGlyphs.DP_CELLS is empty), so we
    // don't even draw an off-ghost dot — that would be a cell the hardware doesn't have.
}

private fun colorFor(segment: Segment, lit: Set<Segment>, on: Color, off: Color): Color =
    if (segment in lit) on else off

private fun DrawScope.drawHorizontalBar(geo: CellGeometry, topY: Float, color: Color) {
    drawRoundRect(
        color = color,
        topLeft = geo.origin + Offset(geo.hInset, topY),
        size = Size(geo.size.width - 2f * geo.hInset, geo.thickness),
        cornerRadius = geo.corner,
    )
}

private fun DrawScope.drawVerticalBar(geo: CellGeometry, left: Boolean, upper: Boolean, color: Color) {
    val x = if (left) 0f else geo.size.width - geo.thickness
    val top = if (upper) geo.vInset else geo.midY + geo.vInset / 2f
    val bottom = if (upper) geo.midY - geo.vInset / 2f else geo.size.height - geo.vInset
    drawRoundRect(
        color = color,
        topLeft = geo.origin + Offset(x, top),
        size = Size(geo.thickness, bottom - top),
        cornerRadius = geo.corner,
    )
}
