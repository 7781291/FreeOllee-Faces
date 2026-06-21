package com.blizzardcaron.freeolleefaces.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

enum class LcdSize(val cellHeightDp: Int) { Md(28), Lg(40), Xl(64) }
enum class LcdTone { Green, Aqua }

/**
 * Segmented LCD readout glowing in a near-black screen well. Renders the value with the watch's
 * real firmware 7-segment glyphs (via [SegmentReadout]) so the preview matches the hardware —
 * not a generic LCD font.
 */
@Composable
fun LcdReadout(
    value: String,
    modifier: Modifier = Modifier,
    size: LcdSize = LcdSize.Lg,
    tone: LcdTone = LcdTone.Green,
) {
    SegmentReadout(value = value, modifier = modifier, cellHeight = size.cellHeightDp.dp, tone = tone)
}
