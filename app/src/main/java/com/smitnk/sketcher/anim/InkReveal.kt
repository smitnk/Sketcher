package com.smitnk.sketcher.anim

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pencil-reveal pacing. The reveal strokes only uncover a pre-rendered ink texture, so what the viewer sees is the
 * amount of NEW ink under the reveal mask, not the length of the path. Time is therefore handed out per chunk of path
 * in proportion to the ink it newly uncovers: the visible ink then grows at a steady rate for the whole animation
 * (long paths over already-revealed or empty paper are skimmed through instead of "drawing nothing" for seconds).
 * Pure Kotlin (no android.*).
 */
object InkReveal {
    /** Pieces a stroke is cut into so the ink inside one long stroke is paced too. Image px. */
    const val CHUNK_LEN = 70f
    /** Ink alpha (0..255) above which a pixel counts as visible ink. */
    const val INK_THRESHOLD = 26
    /** Every piece keeps at least this share of the mean piece weight so no piece is instantaneous. */
    const val WEIGHT_FLOOR = 0.04f
    /** The reveal front never travels faster than this (image px / s of the unscaled timeline). */
    const val MAX_SPEED = 5000f

    /** A stroke cut into consecutive pieces: [points] per piece (shared end vertices) and exact [lengths] (sum = stroke length). */
    class Pieces(val points: List<FloatArray>, val lengths: FloatArray)

    /** Cuts the polyline [p] (x,y pairs, total length [total]) at vertices into pieces of about [maxLen]. */
    fun chunk(p: FloatArray, total: Float, maxLen: Float = CHUNK_LEN): Pieces {
        if (p.size < 4 || total <= maxLen * 1.25f) return Pieces(listOf(p), floatArrayOf(total))
        val pts = ArrayList<FloatArray>()
        val lens = ArrayList<Float>()
        var startV = 0
        var acc = 0f
        var used = 0f
        var i = 2
        while (i < p.size) {
            val dx = p[i] - p[i - 2]; val dy = p[i + 1] - p[i - 1]
            acc += sqrt(dx * dx + dy * dy)
            val last = i + 2 >= p.size
            if (acc >= maxLen && !last) {
                pts.add(p.copyOfRange(startV * 2, i + 2)); lens.add(acc); used += acc
                startV = i / 2; acc = 0f
            }
            i += 2
        }
        pts.add(p.copyOfRange(startV * 2, p.size))
        lens.add(max(0f, total - used)) // exact: pieces add up to the stroke's own length
        return Pieces(pts, lens.toFloatArray())
    }

    /**
     * For each piece in play order: how many ink pixels (alpha >= [INK_THRESHOLD]) lie within [radius] of it and of no
     * earlier piece. [alpha] is the w*h ink alpha (0..255).
     */
    fun newInkPerPiece(alpha: ByteArray, w: Int, h: Int, pieces: List<FloatArray>, radius: Float): FloatArray {
        val covered = BooleanArray(w * h)
        val ri = ceil(radius).toInt()
        val half = IntArray(2 * ri + 1) { k ->
            val dy = (k - ri).toFloat(); val v = radius * radius - dy * dy
            if (v < 0f) -1 else sqrt(v).toInt()
        }
        var cnt = 0
        fun stamp(fx: Float, fy: Float) {
            val cx = fx.toInt(); val cy = fy.toInt()
            for (k in 0..2 * ri) {
                val hw = half[k]; if (hw < 0) continue
                val y = cy + k - ri
                if (y < 0 || y >= h) continue
                val row = y * w
                for (x in max(0, cx - hw)..min(w - 1, cx + hw)) {
                    val j = row + x
                    if (!covered[j]) { covered[j] = true; if ((alpha[j].toInt() and 255) >= INK_THRESHOLD) cnt++ }
                }
            }
        }
        val step = max(1f, radius * 0.5f)
        val out = FloatArray(pieces.size)
        for ((pi, p) in pieces.withIndex()) {
            cnt = 0
            if (p.size >= 2) {
                stamp(p[0], p[1])
                var i = 2
                while (i < p.size) {
                    val ax = p[i - 2]; val ay = p[i - 1]; val dx = p[i] - ax; val dy = p[i + 1] - ay
                    val n = max(1, (sqrt(dx * dx + dy * dy) / step).toInt() + 1)
                    for (s in 1..n) stamp(ax + dx * s / n, ay + dy * s / n)
                    i += 2
                }
            }
            out[pi] = cnt.toFloat()
        }
        return out
    }

    /**
     * Piece durations (seconds, summing to [totalSec]) proportional to new ink + a floor, with the reveal speed capped
     * at [MAX_SPEED] (the surplus goes to the other pieces).
     */
    fun durations(lengths: FloatArray, newInk: FloatArray, totalSec: Double): DoubleArray {
        val n = lengths.size
        if (n == 0) return DoubleArray(0)
        val mean = max(1.0, newInk.sumOf { it.toDouble() } / n)
        val raw = DoubleArray(n) { newInk[it] + WEIGHT_FLOOR * mean }
        val minDur = DoubleArray(n) { lengths[it] / MAX_SPEED.toDouble() }
        val dur = DoubleArray(n)
        val fixed = BooleanArray(n)
        repeat(4) {
            var freeW = 0.0; var fixedT = 0.0
            for (i in 0 until n) if (fixed[i]) fixedT += minDur[i] else freeW += raw[i]
            val scale = if (freeW > 0) max(0.0, totalSec - fixedT) / freeW else 0.0
            for (i in 0 until n) { dur[i] = if (fixed[i]) minDur[i] else raw[i] * scale; if (!fixed[i] && dur[i] < minDur[i]) fixed[i] = true }
        }
        var s = dur.sum()
        if (s <= 0.0) return DoubleArray(n) { totalSec / n }
        val k = totalSec / s
        for (i in 0 until n) dur[i] *= k
        return dur
    }
}
