package com.blizzardcaron.freeolleefaces.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val FreeOlleeColorScheme = darkColorScheme(
    primary = Everforest.Blue,
    onPrimary = Everforest.BgDim,
    primaryContainer = Everforest.BgBlue,
    onPrimaryContainer = Everforest.OnPrimaryContainerBlue,
    secondary = Everforest.Aqua,
    onSecondary = Everforest.BgDim,
    tertiary = Everforest.Green,
    onTertiary = Everforest.BgDim,
    background = Everforest.BgDim,
    onBackground = Everforest.Fg,
    surface = Everforest.Bg0,
    onSurface = Everforest.Fg,
    surfaceVariant = Everforest.Bg1,
    onSurfaceVariant = Everforest.Grey2,
    surfaceContainerLowest = Everforest.BgDim,
    surfaceContainerLow = Everforest.Bg0,
    // Flat card tier: the design's surface-card (and the BottomNav background) is Bg0.
    surfaceContainer = Everforest.Bg0,
    surfaceContainerHigh = Everforest.Bg1,
    surfaceContainerHighest = Everforest.Bg2,
    error = Everforest.Red,
    onError = Everforest.BgDim,
    errorContainer = Everforest.BgRed,
    onErrorContainer = Everforest.TextBright,
    outline = Everforest.Bg3,
    outlineVariant = Everforest.Bg2,
)

@Composable
fun FreeOlleeFacesTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FreeOlleeColorScheme,
        typography = freeOlleeTypography(),
        content = content,
    )
}
