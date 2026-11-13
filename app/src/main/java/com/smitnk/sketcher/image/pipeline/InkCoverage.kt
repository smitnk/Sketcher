package com.smitnk.sketcher.image.pipeline

import com.smitnk.sketcher.image.BrushType
import com.smitnk.sketcher.image.Stroke
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * "Coverage" strokes for the pencil-reveal outline: the vectorised strokes are only reveal paths, so every
 * ink pixel the vectoriser dropped (faint hair, tiny marks) still needs a path passing within the reveal
 * radius or it would only appear when the layer completes. Pure Kotlin.
 */
object InkCoverage {

    /**
     * Returns extra strokes so that, together with [existing], every pixel with `ink >= threshold` lies within
     * [radius] pixels of some stroke. Remaining connected ink is skeleton-traced (like the vectoriser but with
     * no pruning); anything still uncovered (thick blobs, border pixels, isolated pixels) is covered with short
     * scan-line strokes, so the guarantee is unconditional.
     */
    fun coverageStrokes(
        ink: FloatArray,
        w: Int,
        h: Int,
        existing: List<Stroke>,
        radius: Float,
        threshold: Float = 0.2f,
        strokeWidth: Float = 2f,
        maxStrokes: Int = 20_000,
    ): List<Stroke> {
        if (w < 8 || h < 8 || ink.size < w * h) return emptyList()
        val covered = BooleanArray(w * h)
        val st = Stamper(covered, w, h, radius * 0.85f) // safety margin (pixel quantisation + AA): reveal radius is [radius]
        for (s in existing) st.stampPolyline(s.points)

        val out = ArrayList<FloatArray>()
        for (iter in 0 until 4) {
            val res = BooleanArray(w * h)
            var n = 0
            for (i in 0 until w * h) if (ink[i] >= threshold && !covered[i]) { res[i] = true; n++ }
            if (n == 0) break
            for (c in LineArtVectorizer.traceMask(res, w, h)) {
                if (out.size >= maxStrokes) break
                out.add(c)
                st.stampPolyline(c)
            }
        }
        // guaranteed fallback: horizontal runs of still-uncovered ink
        for (y in 0 until h) {
            var x = 0
            while (x < w) {
                val i = y * w + x
                if (ink[i] >= threshold && !covered[i]) {
                    var x1 = x
                    while (x1 + 1 < w && ink[y * w + x1 + 1] >= threshold && !covered[y * w + x1 + 1]) x1++
                    val p = if (x1 == x) floatArrayOf(x + 0.5f, y + 0.5f, x + 0.51f, y + 0.5f)
                    else floatArrayOf(x + 0.5f, y + 0.5f, x1 + 0.5f, y + 0.5f)
                    out.add(p)
                    st.stampPolyline(p)
                    x = x1 + 1
                } else x++
            }
        }
        return order(out).map { Stroke(it, 0xFF000000.toInt(), strokeWidth, BrushType.PEN, 1f) }
    }

    /** Number of pixels with ink >= [threshold] farther than [radius] from every stroke (exact distance test). */
    fun uncoveredCount(ink: FloatArray, w: Int, h: Int, strokes: List<Stroke>, radius: Float, threshold: Float = 0.2f): Int {
        val covered = BooleanArray(w * h)
        val st = Stamper(covered, w, h, radius)
        for (s in strokes) st.stampPolyline(s.points)
        var n = 0
        for (i in 0 until w * h) if (ink[i] >= threshold && !covered[i]) n++
        return n
    }

    /** Stamps discs of radius [r] along polylines into [covered]. */
    private class Stamper(val covered: BooleanArray, val w: Int, val h: Int, val r: Float) {
        private val ri = kotlin.math.ceil(r).toInt()
        private val half = IntArray(2 * ri + 1) { k ->
            val dy = (k - ri).toFloat()
            val v = r * r - dy * dy
            if (v < 0f) -1 else sqrt(v).toInt()
        }

        fun stampPolyline(p: FloatArray) {
            if (p.size < 2) return
            stamp(p[0], p[1])
            var i = 2
            while (i < p.size) {
                val ax = p[i - 2]; val ay = p[i - 1]; val bx = p[i]; val by = p[i + 1]
                val dx = bx - ax; val dy = by - ay
                val len = sqrt(dx * dx + dy * dy)
                val steps = max(1, (len / max(1f, r * 0.5f)).toInt() + 1)
                for (s in 1..steps) {
                    val t = s.toFloat() / steps
                    stamp(ax + dx * t, ay + dy * t)
                }
                i += 2
            }
        }

        private fun stamp(fx: Float, fy: Float) {
            val cx = fx.toInt(); val cy = fy.toInt()
            for (k in 0..2 * ri) {
                val hw = half[k]
                if (hw < 0) continue
                val y = cy + k - ri
                if (y < 0 || y >= h) continue
                val x0 = max(0, cx - hw); val x1 = min(w - 1, cx + hw)
                val row = y * w
                for (x in x0..x1) covered[row + x] = true
            }
        }
    }

    /** Greedy nearest-neighbour ordering (flipping strokes) for small sets, top-to-bottom otherwise. */
    private fun order(c: List<FloatArray>): List<FloatArray> {
        if (c.size < 2) return c
        if (c.size > 2500) return c.sortedBy { it[1] }
        val rest = ArrayList(c.sortedBy { it[1] })
        val result = ArrayList<FloatArray>(c.size)
        var cur = rest.removeAt(0)
        result.add(cur)
        while (rest.isNotEmpty()) {
            val ex = cur[cur.size - 2]; val ey = cur[cur.size - 1]
            var best = -1; var bd = Float.MAX_VALUE; var rev = false
            for (i in rest.indices) {
                val p = rest[i]
                val ds = (p[0] - ex) * (p[0] - ex) + (p[1] - ey) * (p[1] - ey)
                val de = (p[p.size - 2] - ex) * (p[p.size - 2] - ex) + (p[p.size - 1] - ey) * (p[p.size - 1] - ey)
                if (ds < bd) { bd = ds; best = i; rev = false }
                if (de < bd) { bd = de; best = i; rev = true }
            }
            var s = rest.removeAt(best)
            if (rev) {
                val n = s.size / 2
                val o = FloatArray(s.size)
                for (i in 0 until n) { o[i * 2] = s[(n - 1 - i) * 2]; o[i * 2 + 1] = s[(n - 1 - i) * 2 + 1] }
                s = o
            }
            result.add(s)
            cur = s
        }
        return result
    }
}
