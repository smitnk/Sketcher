package com.smitnk.sketcher.export

/**
 * Alpha mapping for exported pencil-reveal ink. The in-app ink texture is pure black RGB with a SOFT alpha
 * (max ~0.93, thin lines mostly 0.1..0.5). That looks fine on paper, but on a transparent PNG that is
 * placed over a dark or coloured background in another app the lines come out faint and washed out.
 * For transparent exports the alpha is pushed through smoothstep([LO], [HI]) so lines are solid, while
 * the anti-aliased edge (the 0..LO..HI ramp) is kept. RGB stays pure black (no grey halo).
 * Pure Kotlin (no android imports) so it can be unit tested and simulated on the JVM.
 */
object InkAlpha {
    const val LO = 0.08f
    const val HI = 0.60f

    fun smoothstep(lo: Float, hi: Float, x: Float): Float {
        val t = ((x - lo) / (hi - lo)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** 256-entry table: input alpha 0..255 -> solid-line alpha 0..255. */
    val LUT: IntArray = IntArray(256) { (smoothstep(LO, HI, it / 255f) * 255f + 0.5f).toInt().coerceIn(0, 255) }

    fun map(alpha: Int): Int = LUT[alpha.coerceIn(0, 255)]
}
