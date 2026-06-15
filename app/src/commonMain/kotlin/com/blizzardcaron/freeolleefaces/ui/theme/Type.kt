package com.blizzardcaron.freeolleefaces.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.blizzardcaron.freeolleefaces.resources.Res
import com.blizzardcaron.freeolleefaces.resources.dseg7_bold
import com.blizzardcaron.freeolleefaces.resources.dseg7_regular
import com.blizzardcaron.freeolleefaces.resources.jetbrainsmono_bold
import com.blizzardcaron.freeolleefaces.resources.jetbrainsmono_regular
import com.blizzardcaron.freeolleefaces.resources.nunito_black
import com.blizzardcaron.freeolleefaces.resources.nunito_bold
import com.blizzardcaron.freeolleefaces.resources.nunito_regular
import com.blizzardcaron.freeolleefaces.resources.nunito_semibold
import org.jetbrains.compose.resources.Font

// Nunito's heaviest face in the design bundle is the 800 weight (tokens define
// --fw-black: 800), so the "black" file is registered at ExtraBold (800).
@Composable
internal fun nunitoFamily() = FontFamily(
    Font(Res.font.nunito_regular, FontWeight.Normal),
    Font(Res.font.nunito_semibold, FontWeight.SemiBold),
    Font(Res.font.nunito_bold, FontWeight.Bold),
    Font(Res.font.nunito_black, FontWeight.ExtraBold),
)

@Composable
internal fun dseg7Family() = FontFamily(
    Font(Res.font.dseg7_regular, FontWeight.Normal),
    Font(Res.font.dseg7_bold, FontWeight.Bold),
)

@Composable
internal fun jetBrainsMonoFamily() = FontFamily(
    Font(Res.font.jetbrainsmono_regular, FontWeight.Normal),
    Font(Res.font.jetbrainsmono_bold, FontWeight.Bold),
)

@Composable
fun freeOlleeTypography(): Typography {
    val sans = nunitoFamily()
    fun s(size: Int, lineHeight: Int, weight: FontWeight) =
        TextStyle(fontFamily = sans, fontSize = size.sp, lineHeight = lineHeight.sp, fontWeight = weight)
    return Typography(
        displaySmall = s(40, 44, FontWeight.ExtraBold),
        headlineLarge = s(32, 38, FontWeight.ExtraBold),
        headlineMedium = s(28, 34, FontWeight.Bold),
        headlineSmall = s(24, 30, FontWeight.ExtraBold),
        titleLarge = s(22, 28, FontWeight.ExtraBold),
        titleMedium = s(18, 24, FontWeight.Bold),
        titleSmall = s(16, 22, FontWeight.Bold),
        bodyLarge = s(16, 22, FontWeight.SemiBold),
        bodyMedium = s(15, 22, FontWeight.Normal),
        bodySmall = s(13, 18, FontWeight.Normal),
        labelLarge = s(14, 20, FontWeight.Bold),
        labelMedium = s(12, 16, FontWeight.Bold),
        labelSmall = s(11, 15, FontWeight.Bold),
    )
}
