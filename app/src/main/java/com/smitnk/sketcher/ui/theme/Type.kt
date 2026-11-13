package com.smitnk.sketcher.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * System fonts only (no bundling): a bookish serif for display/headlines gives the
 * "sketchbook cover" feel; the system sans keeps tool UI crisp and readable.
 * To upgrade later, swap [SketchDisplay] for a res/font family (e.g. Kalam / Fraunces).
 */
val SketchDisplay: FontFamily = FontFamily.Serif
val SketchBody: FontFamily = FontFamily.SansSerif

val SketcherTypography = Typography(
    displayLarge = TextStyle(fontFamily = SketchDisplay, fontWeight = FontWeight.Bold, fontSize = 52.sp, lineHeight = 58.sp, letterSpacing = (-0.02).em),
    displayMedium = TextStyle(fontFamily = SketchDisplay, fontWeight = FontWeight.Bold, fontSize = 40.sp, lineHeight = 46.sp, letterSpacing = (-0.01).em),
    displaySmall = TextStyle(fontFamily = SketchDisplay, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 38.sp),
    headlineLarge = TextStyle(fontFamily = SketchDisplay, fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic, fontSize = 30.sp, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontFamily = SketchDisplay, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontFamily = SketchDisplay, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = SketchBody, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = SketchBody, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.01.em),
    titleSmall = TextStyle(fontFamily = SketchBody, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.01.em),
    bodyLarge = TextStyle(fontFamily = SketchBody, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.03.em),
    bodyMedium = TextStyle(fontFamily = SketchBody, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.02.em),
    bodySmall = TextStyle(fontFamily = SketchBody, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.03.em),
    labelLarge = TextStyle(fontFamily = SketchBody, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.04.em),
    labelMedium = TextStyle(fontFamily = SketchBody, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.05.em),
    labelSmall = TextStyle(fontFamily = SketchBody, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.05.em),
)
