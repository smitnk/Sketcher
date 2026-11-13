package com.smitnk.sketcher.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val SketcherLightColors = lightColorScheme(
    primary = Vermilion,
    onPrimary = Color.White,
    primaryContainer = VermilionContainer,
    onPrimaryContainer = OnVermilionContainer,
    inversePrimary = VermilionLight,
    secondary = BlueGraphite,
    onSecondary = Color.White,
    secondaryContainer = BlueGraphiteContainer,
    onSecondaryContainer = OnBlueGraphiteContainer,
    tertiary = Ochre,
    onTertiary = Color.White,
    tertiaryContainer = OchreContainer,
    onTertiaryContainer = OnOchreContainer,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = PaperHigh,
    onSurfaceVariant = InkSoft,
    surfaceTint = Vermilion,
    inverseSurface = Color(0xFF2A2823),
    inverseOnSurface = Paper,
    error = ErrorLight,
    onError = Color.White,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,
    outline = PencilLine,
    outlineVariant = PencilLineSoft,
    scrim = Color.Black,
    surfaceBright = PaperRaised,
    surfaceDim = PaperHigh,
    surfaceContainerLowest = PaperRaised,
    surfaceContainerLow = PaperLow,
    surfaceContainer = PaperMid,
    surfaceContainerHigh = PaperHigh,
    surfaceContainerHighest = PaperHighest,
)

private val SketcherDarkColors = darkColorScheme(
    primary = VermilionLight,
    onPrimary = OnVermilionLight,
    primaryContainer = VermilionDarkContainer,
    onPrimaryContainer = VermilionContainer,
    inversePrimary = Vermilion,
    secondary = BlueLight,
    onSecondary = OnBlueLight,
    secondaryContainer = BlueDarkContainer,
    onSecondaryContainer = BlueGraphiteContainer,
    tertiary = OchreLight,
    onTertiary = OnOchreLight,
    tertiaryContainer = OchreDarkContainer,
    onTertiaryContainer = OchreContainer,
    background = Studio,
    onBackground = Chalk,
    surface = Studio,
    onSurface = Chalk,
    surfaceVariant = StudioHigh,
    onSurfaceVariant = ChalkSoft,
    surfaceTint = VermilionLight,
    inverseSurface = Chalk,
    inverseOnSurface = Studio,
    error = ErrorDark,
    onError = OnErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
    outline = PencilLineDark,
    outlineVariant = PencilLineSoftDark,
    scrim = Color.Black,
    surfaceBright = StudioHighest,
    surfaceDim = StudioLowest,
    surfaceContainerLowest = StudioLowest,
    surfaceContainerLow = StudioLow,
    surfaceContainer = StudioMid,
    surfaceContainerHigh = StudioHigh,
    surfaceContainerHighest = StudioHighest,
)

private val LocalSketcherColors = staticCompositionLocalOf { LightExtraColors }

/** Non-colour design tokens plus access to theme-aware extra colours. */
object SketcherTokens {
    // Spacing (4dp base, 8dp rhythm)
    val space2: Dp = 2.dp
    val space4: Dp = 4.dp
    val space8: Dp = 8.dp
    val space12: Dp = 12.dp
    val space16: Dp = 16.dp
    val space20: Dp = 20.dp
    val space24: Dp = 24.dp
    val space32: Dp = 32.dp
    val space48: Dp = 48.dp

    // Sizing
    val minTouchTarget: Dp = 48.dp
    val toolButton: Dp = 48.dp
    val toolRailWidth: Dp = 56.dp
    val floatingBarHeight: Dp = 48.dp
    val layerThumb: Dp = 48.dp
    val layersDrawerWidth: Dp = 280.dp
    val screenMargin: Dp = 16.dp
    val iconSize: Dp = 24.dp
    val iconStroke: Dp = 2.dp
    val maxContentWidth: Dp = 840.dp

    // Elevation (tonal first; shadows only for floating chrome)
    val elevationFlat: Dp = 0.dp
    val elevationFloating: Dp = 6.dp
    val elevationSheet: Dp = 3.dp

    // Motion (ms)
    const val MotionShort = 120
    const val MotionMedium = 240
    const val MotionLong = 400

    /** Extra colours for the current theme: paper, ink, canvasBackdrop, etc. */
    val colors: SketcherExtraColors
        @Composable @ReadOnlyComposable get() = LocalSketcherColors.current
}

@Composable
fun SketcherTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) SketcherDarkColors else SketcherLightColors
    val extras = if (darkTheme) DarkExtraColors else LightExtraColors
    CompositionLocalProvider(LocalSketcherColors provides extras) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = SketcherTypography,
            shapes = SketcherShapes,
            content = content,
        )
    }
}
