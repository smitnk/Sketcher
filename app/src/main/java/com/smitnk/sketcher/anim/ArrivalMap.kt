package com.smitnk.sketcher.anim

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * "Arrival map" of a pencil-reveal layer: for every pixel within the reveal radius of any reveal stroke, the
 * cumulative timeline length at which the reveal front first reaches it. A pixel is visible when
 * `arrival <= allowance`, where the allowance is the layer's revealed length (same unit the renderer's budget uses:
 * strokes in list order, [Stroke.length] each, so stroke i starts at the sum of the previous lengths).
 * Pure Kotlin (no android.*).
 *
 * [data] is a w*h grid (the drawing scaled by [scale]) in length units; [NEVER] marks pixels no stroke covers.
 */
class ArrivalMap(val w: Int, val h: Int, val scale: Float, val total: Float, val data: FloatArray) {
    companion object { const val NEVER = Float.MAX_VALUE }
}

object ArrivalBaker {
    /**
     * Rasterises every stroke as a chain of round-capped segments of width 2*[radius] (drawing px) into a grid of
     * [w] x [h] cells (drawing size times [scale]), min-compositing the arrival so the earliest stroke wins.
     * [strokes] are x,y polylines in drawing px; [lengths] their [Stroke.length]s (they define the stroke start
     * offsets exactly like the renderer's budget); inside a stroke the arrival grows with the walked distance.
     */
    fun bake(w: Int, h: Int, scale: Float, strokes: List<FloatArray>, lengths: FloatArray, radius: Float): ArrivalMap {
        val data = FloatArray(w * h) { ArrivalMap.NEVER }
        val r = max(0.5f, radius) * scale
        val r2 = r * r
        var base = 0f
        for ((si, p) in strokes.withIndex()) {
            val len = lengths[si]
            if (p.size >= 4) {
                var walked = 0f
                var i = 2
                while (i < p.size) {
                    val ax = p[i - 2] * scale; val ay = p[i - 1] * scale
                    val bx = p[i] * scale; val by = p[i + 1] * scale
                    val segLen = sqrt((p[i] - p[i - 2]).let { it * it } + (p[i + 1] - p[i - 1]).let { it * it })
                    if (segLen > 0f || i == 2) capsule(data, w, h, ax, ay, bx, by, r, r2, base + walked, segLen)
                    walked += segLen
                    i += 2
                }
            }
            base += len
        }
        return ArrivalMap(w, h, scale, base, data)
    }

    /** Scanline capsule: per row only the x-span the segment (grown by r) can touch, then an exact distance test. */
    private fun capsule(d: FloatArray, w: Int, h: Int, ax: Float, ay: Float, bx: Float, by: Float, r: Float, r2: Float, c0: Float, segLen: Float) {
        val dx = bx - ax; val dy = by - ay
        val l2 = dx * dx + dy * dy
        val y0 = max(0, floor(min(ay, by) - r).toInt())
        val y1 = min(h - 1, ceil(max(ay, by) + r).toInt())
        for (y in y0..y1) {
            val py = y + 0.5f
            var xa: Float; var xb: Float
            if (abs(dy) < 1e-6f) {
                xa = min(ax, bx); xb = max(ax, bx)
            } else {
                val t1 = ((py - r - ay) / dy).coerceIn(0f, 1f); val t2 = ((py + r - ay) / dy).coerceIn(0f, 1f)
                val x1 = ax + dx * t1; val x2 = ax + dx * t2
                xa = min(x1, x2); xb = max(x1, x2)
            }
            val x0 = max(0, floor(xa - r).toInt())
            val xe = min(w - 1, ceil(xb + r).toInt())
            val row = y * w
            for (x in x0..xe) {
                val px = x + 0.5f
                val ex = px - ax; val ey = py - ay
                val t = if (l2 > 0f) ((ex * dx + ey * dy) / l2).coerceIn(0f, 1f) else 0f
                val qx = ex - dx * t; val qy = ey - dy * t
                if (qx * qx + qy * qy <= r2) {
                    val a = c0 + t * segLen
                    if (a < d[row + x]) d[row + x] = a
                }
            }
        }
    }
}
