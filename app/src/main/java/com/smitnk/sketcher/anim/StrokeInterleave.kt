package com.smitnk.sketcher.anim

import kotlin.math.floor
import kotlin.math.max

/**
 * Spreads [extra] strokes (e.g. InkCoverage hairs) through [base] instead of appending them at the end: each extra
 * stroke is placed right after the base stroke whose path passes closest to its start. Keeps the relative order
 * of both lists; extras with the same anchor keep their own order. Pure Kotlin, grid-accelerated.
 */
object StrokeInterleave {
    private const val CELL = 48f

    fun <T> interleave(base: List<T>, extra: List<T>, points: (T) -> FloatArray): List<T> {
        if (extra.isEmpty() || base.isEmpty()) return base + extra
        // spatial hash of base vertices (cell -> base index, x, y)
        val grid = HashMap<Long, ArrayList<Int>>()
        val gx = HashMap<Long, ArrayList<Float>>()
        val gy = HashMap<Long, ArrayList<Float>>()
        fun key(cx: Int, cy: Int) = (cx.toLong() shl 32) xor (cy.toLong() and 0xFFFFFFFFL)
        var minC = Int.MAX_VALUE; var maxC = Int.MIN_VALUE
        for ((bi, s) in base.withIndex()) {
            val p = points(s)
            var i = 0
            while (i + 1 < p.size) {
                val cx = floor(p[i] / CELL).toInt(); val cy = floor(p[i + 1] / CELL).toInt()
                val kk = key(cx, cy)
                grid.getOrPut(kk) { ArrayList() }.add(bi)
                gx.getOrPut(kk) { ArrayList() }.add(p[i]); gy.getOrPut(kk) { ArrayList() }.add(p[i + 1])
                minC = minOf(minC, cx, cy); maxC = max(maxC, max(cx, cy))
                i += 2
            }
        }
        val after = Array(base.size) { ArrayList<T>() }
        val maxRing = max(1, maxC - minC + 2)
        for (e in extra) {
            val p = points(e)
            if (p.size < 2) { after[base.size - 1].add(e); continue }
            val ex = p[0]; val ey = p[1]
            val ecx = floor(ex / CELL).toInt(); val ecy = floor(ey / CELL).toInt()
            var best = -1; var bd = Float.MAX_VALUE
            var r = 0
            while (r <= maxRing) {
                for (cy in ecy - r..ecy + r) for (cx in ecx - r..ecx + r) {
                    if (r > 0 && cx > ecx - r && cx < ecx + r && cy > ecy - r && cy < ecy + r) continue
                    val kk = key(cx, cy)
                    val ids = grid[kk] ?: continue
                    val xs = gx[kk]!!; val ys = gy[kk]!!
                    for (j in ids.indices) {
                        val dx = xs[j] - ex; val dy = ys[j] - ey
                        val d = dx * dx + dy * dy
                        if (d < bd) { bd = d; best = ids[j] }
                    }
                }
                // everything in farther rings is at least r*CELL away
                if (best >= 0 && bd <= (r * CELL) * (r * CELL)) break
                r++
            }
            after[if (best >= 0) best else base.size - 1].add(e)
        }
        val out = ArrayList<T>(base.size + extra.size)
        for (i in base.indices) { out.add(base[i]); out.addAll(after[i]) }
        return out
    }
}
