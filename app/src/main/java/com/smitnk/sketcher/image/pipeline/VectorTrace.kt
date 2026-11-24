package com.smitnk.sketcher.image.pipeline

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/** One traced region. [pathData] is an SVG `d` string (all loops of the region; fill with even-odd for holes). */
data class VectorShape(val regionId: Int, val argb: Int, val area: Int, val pathData: String)

/**
 * Region map -> vector shapes. Pure-Kotlin port of the subset of vtracer (MIT, visioncortex) needed for an
 * already-clustered map: crack-following boundary walk on pixel edges, simplification, spline curve fitting.
 * See docs/vtracer.md.
 */
object VectorTrace {

    // Directions on the pixel-corner lattice (y down): E, S, W, N. Walking an edge, its region is on the right.
    private val DX = intArrayOf(1, 0, -1, 0)
    private val DY = intArrayOf(0, 1, 0, -1)

    /** Simplification tolerance in pixels (removes 1 px staircases). */
    private const val EPSILON = 1.0

    /** Turns sharper than this (degrees) stay corners; gentler ones become smooth curves (vtracer default 60). */
    private const val CORNER_DEG = 60.0

    /**
     * Traces every region with at least [minArea] pixels; [colours] is indexed by region id (as from
     * [LineFill.flatColours]). Labels outside 0 until count are treated as "no region". Largest area first.
     */
    fun trace(map: RegionMap, colours: IntArray, minArea: Int = 16): List<VectorShape> {
        val w = map.width
        val h = map.height
        val labels = map.labels
        require(labels.size >= w * h) { "labels smaller than width*height" }
        fun label(x: Int, y: Int): Int =
            if (x < 0 || y < 0 || x >= w || y >= h) -1 else labels[y * w + x].let { if (it in 0 until map.count) it else -1 }

        val area = IntArray(map.count)
        for (i in 0 until w * h) { val l = labels[i]; if (l in 0 until map.count) area[l]++ }

        val vw = w + 1
        // Label on the right of edge (vertex v, dir d), or -1 when no boundary edge exists there.
        fun edgeLabel(vx: Int, vy: Int, d: Int): Int {
            val (rx, ry, lx, ly) = when (d) {
                0 -> intArrayOf(vx, vy, vx, vy - 1)
                1 -> intArrayOf(vx - 1, vy, vx, vy)
                2 -> intArrayOf(vx - 1, vy - 1, vx - 1, vy)
                else -> intArrayOf(vx, vy - 1, vx - 1, vy - 1)
            }
            val r = label(rx, ry)
            return if (r >= 0 && r != label(lx, ly)) r else -1
        }

        val used = BooleanArray(vw * (h + 1) * 4)
        val loops = arrayOfNulls<MutableList<List<IntArray>>>(map.count)
        for (vy in 0..h) for (vx in 0..w) for (d0 in 0..3) {
            val e0 = (vy * vw + vx) * 4 + d0
            if (used[e0]) continue
            val r = edgeLabel(vx, vy, d0)
            if (r < 0 || area[r] < minArea) continue
            // Walk the closed loop, recording only corner vertices.
            val pts = ArrayList<IntArray>()
            var x = vx; var y = vy; var d = d0
            while (true) {
                used[(y * vw + x) * 4 + d] = true
                x += DX[d]; y += DY[d]
                // Prefer right turn, then straight, then left (keeps diagonal-touching pixels apart).
                var nd = -1
                for (c in intArrayOf((d + 1) % 4, d, (d + 3) % 4)) {
                    if (!used[(y * vw + x) * 4 + c] && edgeLabel(x, y, c) == r) { nd = c; break }
                }
                if (nd != d) pts.add(intArrayOf(x, y))
                if (nd < 0) break
                d = nd
            }
            (loops[r] ?: mutableListOf<List<IntArray>>().also { loops[r] = it }).add(pts)
        }

        val out = ArrayList<VectorShape>()
        for (r in 0 until map.count) {
            val ls = loops[r] ?: continue
            val sb = StringBuilder()
            for (loop in ls) appendLoop(sb, simplify(loop))
            out.add(VectorShape(r, colours.getOrElse(r) { 0 }, area[r], sb.toString().trim()))
        }
        out.sortByDescending { it.area }
        return out
    }

    /** Closed-polygon Ramer-Douglas-Peucker on the corner points. */
    private fun simplify(pts: List<IntArray>): List<DoubleArray> {
        val p = pts.map { doubleArrayOf(it[0].toDouble(), it[1].toDouble()) }
        if (p.size <= 4) return p
        // Split at point 0 and the point farthest from it, simplify both halves.
        var far = 0; var best = -1.0
        for (i in p.indices) { val dd = dist2(p[0], p[i]); if (dd > best) { best = dd; far = i } }
        val keep = BooleanArray(p.size + 1)
        keep[0] = true; keep[far] = true; keep[p.size] = true
        val ring = p + listOf(p[0])
        rdp(ring, 0, far, keep); rdp(ring, far, p.size, keep)
        val res = p.indices.filter { keep[it] }.map { p[it] }
        return if (res.size >= 3) res else p
    }

    private fun rdp(p: List<DoubleArray>, a: Int, b: Int, keep: BooleanArray) {
        if (b - a < 2) return
        var idx = -1; var best = EPSILON
        for (i in a + 1 until b) { val d = segDist(p[i], p[a], p[b]); if (d > best) { best = d; idx = i } }
        if (idx < 0) return
        keep[idx] = true
        rdp(p, a, idx, keep); rdp(p, idx, b, keep)
    }

    /** Catmull-Rom spline through the points as cubic Béziers; sharp corners keep straight segments. */
    private fun appendLoop(sb: StringBuilder, p: List<DoubleArray>) {
        val n = p.size
        if (n < 3) return
        fun at(i: Int) = p[((i % n) + n) % n]
        val tan = Array(n) { i ->
            val a = at(i - 1); val b = at(i); val c = at(i + 1)
            if (turnDeg(a, b, c) > CORNER_DEG) doubleArrayOf(0.0, 0.0)
            else doubleArrayOf((c[0] - a[0]) / 6, (c[1] - a[1]) / 6)
        }
        sb.append('M').append(f(p[0][0])).append(' ').append(f(p[0][1]))
        for (i in 0 until n) {
            val a = at(i); val b = at(i + 1); val ta = tan[i]; val tb = tan[(i + 1) % n]
            if (ta[0] == 0.0 && ta[1] == 0.0 && tb[0] == 0.0 && tb[1] == 0.0) {
                if (i < n - 1) sb.append('L').append(f(b[0])).append(' ').append(f(b[1]))
            } else {
                sb.append('C').append(f(a[0] + ta[0])).append(' ').append(f(a[1] + ta[1])).append(' ')
                    .append(f(b[0] - tb[0])).append(' ').append(f(b[1] - tb[1])).append(' ')
                    .append(f(b[0])).append(' ').append(f(b[1]))
            }
        }
        sb.append("Z ")
    }

    private fun turnDeg(a: DoubleArray, b: DoubleArray, c: DoubleArray): Double {
        val ux = b[0] - a[0]; val uy = b[1] - a[1]; val vx = c[0] - b[0]; val vy = c[1] - b[1]
        val l = sqrt((ux * ux + uy * uy) * (vx * vx + vy * vy))
        if (l == 0.0) return 0.0
        return Math.toDegrees(acos(((ux * vx + uy * vy) / l).coerceIn(-1.0, 1.0)))
    }

    private fun dist2(a: DoubleArray, b: DoubleArray) = (a[0] - b[0]).let { it * it } + (a[1] - b[1]).let { it * it }

    private fun segDist(p: DoubleArray, a: DoubleArray, b: DoubleArray): Double {
        val dx = b[0] - a[0]; val dy = b[1] - a[1]
        val l2 = dx * dx + dy * dy
        if (l2 == 0.0) return sqrt(dist2(p, a))
        return abs(dy * p[0] - dx * p[1] + b[0] * a[1] - b[1] * a[0]) / sqrt(l2)
    }

    /** Locale-independent number, at most 2 decimals. */
    internal fun f(v: Double): String {
        val r = Math.round(v * 100) / 100.0
        return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
    }
}
