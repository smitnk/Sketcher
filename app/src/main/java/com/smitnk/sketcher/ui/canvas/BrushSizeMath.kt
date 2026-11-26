package com.smitnk.sketcher.ui.canvas

import kotlin.math.exp
import kotlin.math.ln

/** Pure size maths: slider 0..1 <-> canvas pixels (log feel), and the on-screen preview circle. */
object BrushSizeMath {
    const val MIN_PX = 1f
    const val MAX_PX = 200f

    fun toPx(t: Float): Float = MIN_PX * exp(t.coerceIn(0f, 1f) * ln(MAX_PX / MIN_PX))

    fun toSlider(px: Float): Float = (ln(px.coerceIn(MIN_PX, MAX_PX) / MIN_PX) / ln(MAX_PX / MIN_PX)).coerceIn(0f, 1f)

    /** Radius in screen px of a brush [sizePx] canvas px across; [scale] = screen px per canvas px (fit * zoom). */
    fun screenRadius(sizePx: Float, scale: Float): Float = sizePx / 2f * scale
}
