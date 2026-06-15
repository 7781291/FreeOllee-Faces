package com.blizzardcaron.freeolleefaces.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blizzardcaron.freeolleefaces.ui.theme.BrandColors
import com.blizzardcaron.freeolleefaces.ui.theme.dseg7Family

enum class LcdSize(val fontSizeSp: Int) { Md(22), Lg(34), Xl(56) }
enum class LcdTone { Green, Aqua }

/** Segmented LCD readout (DSEG7) glowing green in a near-black screen well. */
@Composable
fun LcdReadout(
    value: String,
    modifier: Modifier = Modifier,
    size: LcdSize = LcdSize.Lg,
    tone: LcdTone = LcdTone.Green,
) {
    val lit = if (tone == LcdTone.Aqua) BrandColors.LcdOnAqua else BrandColors.LcdOn
    Text(
        text = value,
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(BrandColors.LcdScreen)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        style = TextStyle(
            fontFamily = dseg7Family(),
            fontSize = size.fontSizeSp.sp,
            color = lit,
            shadow = Shadow(color = lit, offset = Offset.Zero, blurRadius = 10f),
        ),
    )
}
