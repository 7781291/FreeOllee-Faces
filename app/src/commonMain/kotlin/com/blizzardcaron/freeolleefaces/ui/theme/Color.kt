package com.blizzardcaron.freeolleefaces.ui.theme

import androidx.compose.ui.graphics.Color

// Everforest (dark, medium) — mirrors design tokens/colors.css.
internal object Everforest {
    val BgDim = Color(0xFF232A2E) // app canvas
    val Bg0 = Color(0xFF2D353B) // card surface
    val Bg1 = Color(0xFF343F44) // raised surface
    val Bg2 = Color(0xFF3D484D) // inset / hover / outline-variant
    val Bg3 = Color(0xFF475258) // border
    val Bg4 = Color(0xFF4F585E) // strong border

    val BgGreen = Color(0xFF3C4841) // legacy green wash — kept for reference, no longer wired to primaryContainer
    val BgBlue = Color(0xFF33454A) // active/selected wash (primaryContainer), blue theme
    val BgRed = Color(0xFF543A48) // error container

    val Fg = Color(0xFFD3C6AA) // primary text (warm tan)
    val Grey2 = Color(0xFF9DA9A0) // secondary text
    val TextBright = Color(0xFFE8E0CC)

    val Red = Color(0xFFE67E80)
    val Yellow = Color(0xFFDBBC7F)
    val Green = Color(0xFFA7C080) // legacy signature accent — kept for reference, no longer primary
    val Aqua = Color(0xFF83C092)
    val Blue = Color(0xFF7FBBB3) // signature accent

    // Named for its M3 role (light text on the primary container); the design has
    // no separate palette name for this tint.
    val OnPrimaryContainer = Color(0xFFCBE0A6) // legacy green-tinted text — kept for reference
    val OnPrimaryContainerBlue = Color(0xFFBFE3E6) // blue-tinted text
    val MarkerRed = Color(0xFFF85552) // wordmark only
}

/** LCD readout colors not represented in the Material color scheme. */
object BrandColors {
    val LcdScreen = Color(0xFF1A1F1C)
    val LcdOn = Everforest.Blue
    val LcdOnAqua = Everforest.Aqua
    val LcdOff = Everforest.Blue.copy(alpha = 0.10f)
    val MarkerRed = Everforest.MarkerRed
}
