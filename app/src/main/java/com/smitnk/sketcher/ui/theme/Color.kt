package com.smitnk.sketcher.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

// ---- Raw palette: "Artist's sketchbook" (warm paper, ink, vermilion pencil) ----

// Light (paper mode)
internal val Paper = Color(0xFFF6F1E7)
internal val PaperRaised = Color(0xFFFFFDF8)
internal val PaperLow = Color(0xFFFAF6EC)
internal val PaperMid = Color(0xFFF1EBDD)
internal val PaperHigh = Color(0xFFEAE3D2)
internal val PaperHighest = Color(0xFFE2DAC7)
internal val Ink = Color(0xFF1B1A17)
internal val InkSoft = Color(0xFF5B574E)
internal val Vermilion = Color(0xFFC23B22)
internal val VermilionContainer = Color(0xFFFFDAD0)
internal val OnVermilionContainer = Color(0xFF4A1006)
internal val BlueGraphite = Color(0xFF3E6A8F)
internal val BlueGraphiteContainer = Color(0xFFD3E4F7)
internal val OnBlueGraphiteContainer = Color(0xFF0B2A44)
internal val Ochre = Color(0xFF8A6A12)
internal val OchreContainer = Color(0xFFF7E3A6)
internal val OnOchreContainer = Color(0xFF3A2C00)
internal val PencilLine = Color(0xFF8A8478)
internal val PencilLineSoft = Color(0xFFD5CDBB)
internal val ErrorLight = Color(0xFFB3261E)
internal val ErrorContainerLight = Color(0xFFF9DEDC)
internal val OnErrorContainerLight = Color(0xFF410E0B)

// Dark (studio mode)
internal val Studio = Color(0xFF14130F)
internal val StudioLowest = Color(0xFF0E0D0A)
internal val StudioLow = Color(0xFF1C1A16)
internal val StudioMid = Color(0xFF26231E)
internal val StudioHigh = Color(0xFF312E28)
internal val StudioHighest = Color(0xFF3C3832)
internal val Chalk = Color(0xFFEDE7DA)
internal val ChalkSoft = Color(0xFFBDB5A5)
internal val VermilionLight = Color(0xFFFF8A6B)
internal val OnVermilionLight = Color(0xFF3A0D03)
internal val VermilionDarkContainer = Color(0xFF7A2614)
internal val BlueLight = Color(0xFF9CC4EA)
internal val OnBlueLight = Color(0xFF0B2A44)
internal val BlueDarkContainer = Color(0xFF254B6C)
internal val OchreLight = Color(0xFFE6C35C)
internal val OnOchreLight = Color(0xFF3A2C00)
internal val OchreDarkContainer = Color(0xFF5E4800)
internal val PencilLineDark = Color(0xFF8C8576)
internal val PencilLineSoftDark = Color(0xFF3E3A33)
internal val ErrorDark = Color(0xFFF2B8B5)
internal val OnErrorDark = Color(0xFF601410)
internal val ErrorContainerDark = Color(0xFF8C1D18)
internal val OnErrorContainerDark = Color(0xFFF9DEDC)

/** Extra, non-Material colours exposed through [SketcherTokens.colors]. */
@Immutable
data class SketcherExtraColors(
    /** Sheet of paper behind the artwork (export background / default canvas). */
    val paper: Color,
    /** Default stroke colour for outlines. */
    val ink: Color,
    /** Area around the artboard in the editor (never pure black or white). */
    val canvasBackdrop: Color,
    /** Floating chrome (tool rail, top bar pill) over the canvas. */
    val floatingSurface: Color,
    val onFloatingSurface: Color,
    /** Scrim behind sheets/drawers. */
    val scrim: Color,
    /** Checkerboard tones for transparent areas. */
    val checkerA: Color,
    val checkerB: Color,
    /** Shading layer tint shown in thumbnails/mini previews. */
    val shadingTint: Color,
    /** Colour-fill layer tint shown in thumbnails/mini previews. */
    val fillTint: Color,
    val success: Color,
)

internal val LightExtraColors = SketcherExtraColors(
    paper = Color(0xFFFFFDF8),
    ink = Ink,
    canvasBackdrop = Color(0xFFDDD5C2),
    floatingSurface = Color(0xF2FFFDF8),
    onFloatingSurface = Ink,
    scrim = Color(0x661B1A17),
    checkerA = Color(0xFFFFFFFF),
    checkerB = Color(0xFFE4DECF),
    shadingTint = Color(0xFF6B6558),
    fillTint = Color(0xFFE9A98F),
    success = Color(0xFF2E6B3A),
)

internal val DarkExtraColors = SketcherExtraColors(
    paper = Color(0xFFF1EBDD),
    ink = Ink,
    canvasBackdrop = Color(0xFF0B0A08),
    floatingSurface = Color(0xF21C1A16),
    onFloatingSurface = Chalk,
    scrim = Color(0xA6000000),
    checkerA = Color(0xFF2A2722),
    checkerB = Color(0xFF211F1B),
    shadingTint = Color(0xFF9A9384),
    fillTint = Color(0xFFD9785A),
    success = Color(0xFF8CD19A),
)
