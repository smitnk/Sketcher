package com.smitnk.sketcher.image.pipeline

import kotlin.math.abs

/**
 * Post-processing for the TEED / MTEED edge detectors ("Fast edges" engine).
 *
 * The networks emit an edge probability whose paper level is NOT 0 (TEED ~0.44, MTEED ~0.10) and whose lines are
 * 2-4 px wide and soft. [refine] turns that into ink (0..1, 1 = line): adaptive floor/ceiling normalisation,
 * hysteresis (weak edges survive only when attached to a strong one) and soft non-maximum suppression (ridge
 * pixels keep their value, flanks are attenuated) so the vectoriser and pencil texture see crisp, thin contours.
 */
object FastEdges {
    private const val BINS = 1024

    /** Paper level = this percentile of the map; line level = [HI_PCT]. Most pixels are paper. */
    private const val LO_PCT = 0.50f
    private const val HI_PCT = 0.995f

    /** Flank pixels (not a local maximum across the line) keep this share of their value. */
    private const val FLANK_KEEP = 0.35f

    /**
     * @param prob model output (w*h), higher = edge, any floor.
     * @param detail 0..1 (more/less lines); lowers the hysteresis thresholds.
     * @return new ink map (w*h, 0..1).
     */
    fun refine(prob: FloatArray, w: Int, h: Int, detail: Float): FloatArray {
        require(w > 0 && h > 0 && prob.size >= w * h) { "bad map" }
        val n = w * h
        val v = normalise(prob, n)
        val d = detail.coerceIn(0f, 1f)
        val lowT = 0.30f - 0.18f * d
        val highT = 0.62f - 0.25f * d
        val keep = hysteresis(v, w, h, lowT, highT)
        val out = FloatArray(n)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val a = v[i]
            if (!keep[i]) continue
            out[i] = if (isRidge(v, w, h, x, y)) a else a * FLANK_KEEP
        }
        return out
    }

    /** (p - paper) / (line - paper), clamped to 0..1, from the histogram percentiles. */
    internal fun normalise(prob: FloatArray, n: Int): FloatArray {
        var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE
        for (i in 0 until n) { val p = prob[i]; if (p < mn) mn = p; if (p > mx) mx = p }
        val span = mx - mn
        val v = FloatArray(n)
        if (!(span > 1e-6f)) return v
        val bins = IntArray(BINS)
        for (i in 0 until n) bins[(((prob[i] - mn) / span) * (BINS - 1)).toInt().coerceIn(0, BINS - 1)]++
        fun pct(q: Float): Float {
            val target = (q * n).toInt()
            var acc = 0
            for (b in 0 until BINS) { acc += bins[b]; if (acc > target) return mn + span * (b + 0.5f) / BINS }
            return mx
        }
        val lo = pct(LO_PCT)
        val hi = pct(HI_PCT)
        val inv = if (hi - lo > 1e-4f) 1f / (hi - lo) else 0f
        for (i in 0 until n) v[i] = ((prob[i] - lo) * inv).coerceIn(0f, 1f)
        return v
    }

    /** Pixels >= [low] that are 8-connected to a pixel >= [high]. */
    internal fun hysteresis(v: FloatArray, w: Int, h: Int, low: Float, high: Float): BooleanArray {
        val keep = BooleanArray(w * h)
        val stack = IntArray(w * h)
        var sp = 0
        for (i in 0 until w * h) if (v[i] >= high) { keep[i] = true; stack[sp++] = i }
        while (sp > 0) {
            val i = stack[--sp]
            val x = i % w; val y = i / w
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = x + dx; val ny = y + dy
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                val j = ny * w + nx
                if (!keep[j] && v[j] >= low) { keep[j] = true; stack[sp++] = j }
            }
        }
        return keep
    }

    /** True when (x,y) is >= both neighbours along the gradient direction (quantised to 4 directions). */
    private fun isRidge(v: FloatArray, w: Int, h: Int, x: Int, y: Int): Boolean {
        val x0 = if (x > 0) x - 1 else x; val x1 = if (x < w - 1) x + 1 else x
        val y0 = if (y > 0) y - 1 else y; val y1 = if (y < h - 1) y + 1 else y
        val gx = v[y * w + x1] - v[y * w + x0]
        val gy = v[y1 * w + x] - v[y0 * w + x]
        val ax = abs(gx); val ay = abs(gy)
        val dx: Int; val dy: Int
        when {
            ax > 2.414f * ay -> { dx = 1; dy = 0 }
            ay > 2.414f * ax -> { dx = 0; dy = 1 }
            (gx > 0) == (gy > 0) -> { dx = 1; dy = 1 }
            else -> { dx = 1; dy = -1 }
        }
        val c = v[y * w + x]
        val ax1 = (x + dx).coerceIn(0, w - 1); val ay1 = (y + dy).coerceIn(0, h - 1)
        val bx1 = (x - dx).coerceIn(0, w - 1); val by1 = (y - dy).coerceIn(0, h - 1)
        return c >= v[ay1 * w + ax1] && c >= v[by1 * w + bx1]
    }
}

/** Wraps a TEED/MTEED model: its raw edge probability becomes refined ink. [detail] is read per call. */
class FastEdgesModel(private val inner: LineArtModel, private val detail: () -> Float) : LineArtModel {
    override fun infer(argb: IntArray, w: Int, h: Int): FloatArray =
        FastEdges.refine(inner.infer(argb, w, h), w, h, detail())
}
