package com.smitnk.sketcher.image

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** What the generator produces. */
enum class DrawMode(val label: String) {
    OUTLINE("Outline only"),
    OUTLINE_SHADING("Outline + Shading"),
    COLOUR_FILL("Colour outline + Fill"),
}

/**
 * Colour support: recolours outline strokes from the photo and builds hand-filled colour regions
 * (k-means quantisation -> connected regions -> continuous zig-zag scribble strokes).
 * Pure Kotlin, no Android dependencies.
 */
object ColorStrokeGenerator {

    /** Longest side of the working grid used for quantising / region finding. */
    private const val GRID_MAX = 512
    private const val FILL_ANGLE_DEG = 28.0

    // ------------------------------------------------------------ colour outline

    /**
     * Returns copies of [strokes] coloured with the (slightly darkened) average image colour along
     * each stroke. [strokes] are in a [drawW]x[drawH] space; [pixels] is the source [imgW]x[imgH] ARGB image.
     */
    fun colorizeOutline(
        strokes: List<Stroke>,
        pixels: IntArray, imgW: Int, imgH: Int,
        drawW: Int, drawH: Int,
        darken: Float = 0.72f,
    ): List<Stroke> {
        val sx = imgW.toFloat() / drawW
        val sy = imgH.toFloat() / drawH
        return strokes.map { s ->
            val p = s.points
            var r = 0f; var g = 0f; var b = 0f; var n = 0
            fun sample(x: Float, y: Float) {
                val cx = (x * sx).toInt().coerceIn(0, imgW - 1)
                val cy = (y * sy).toInt().coerceIn(0, imgH - 1)
                for (dy in -1..1) for (dx in -1..1) {
                    val c = pixels[(cy + dy).coerceIn(0, imgH - 1) * imgW + (cx + dx).coerceIn(0, imgW - 1)]
                    val a = (c ushr 24) / 255f
                    r += ((c shr 16) and 255) * a + 255 * (1 - a)
                    g += ((c shr 8) and 255) * a + 255 * (1 - a)
                    b += (c and 255) * a + 255 * (1 - a)
                    n++
                }
            }
            if (p.size >= 2) sample(p[0], p[1])
            var carry = 0f
            var i = 2
            while (i < p.size) {
                val dx = p[i] - p[i - 2]
                val dy = p[i + 1] - p[i - 1]
                val len = sqrt(dx * dx + dy * dy)
                var t = 3f - carry
                while (t <= len) {
                    val f = t / len
                    sample(p[i - 2] + dx * f, p[i - 1] + dy * f)
                    t += 3f
                }
                carry = (carry + len) % 3f
                i += 2
            }
            if (n == 0) s else {
                var rr = r / n * darken; var gg = g / n * darken; var bb = b / n * darken
                val lum = 0.299f * rr + 0.587f * gg + 0.114f * bb
                if (lum > 150f) { val k = 150f / lum; rr *= k; gg *= k; bb *= k }
                val col = (0xFF shl 24) or (rr.roundToInt().coerceIn(0, 255) shl 16) or
                    (gg.roundToInt().coerceIn(0, 255) shl 8) or bb.roundToInt().coerceIn(0, 255)
                Stroke(p, col, s.width)
            }
        }
    }

    // ------------------------------------------------------------ fill strokes

    private class Run(val comp: Int, val line: Int, val u0: Float, val u1: Float)

    /**
     * Fill strokes for the photo, in [outW]x[outH] coordinates (same space as the outline).
     * [colors] 6..16, [spacing] distance between scribble lines in output pixels.
     * Regions are ordered large -> small (light -> dark among similar sizes).
     */
    fun generateFill(
        pixels: IntArray, imgW: Int, imgH: Int,
        outW: Int, outH: Int,
        colors: Int, spacing: Float,
        skipNearWhite: Boolean = true,
    ): List<Stroke> {
        require(pixels.size >= imgW * imgH) { "pixel array too small" }
        val k0 = colors.coerceIn(2, 32)
        val f = min(1f, GRID_MAX.toFloat() / max(outW, outH))
        val gw = max(1, (outW * f).roundToInt())
        val gh = max(1, (outH * f).roundToInt())
        val sx = outW.toFloat() / gw
        val sy = outH.toFloat() / gh
        val sp = max(1.5f, spacing)
        val spG = sp / sx
        val width = sp * 1.3f

        // 1. resample to grid (box average), RGB floats
        val rgb = resample(pixels, imgW, imgH, gw, gh)
        // 2. k-means
        val (centers, label0) = kmeans(rgb, gw, gh, k0)
        val k = centers.size / 3
        val label = modeFilter(label0, gw, gh, k)

        // 3. connected components, drop tiny and absorb them
        val minArea = max(12, (gw * gh * 0.0005f).toInt())
        val comp = IntArray(gw * gh) { -1 }
        val compLabel = ArrayList<Int>()
        val compArea = ArrayList<Int>()
        val stack = IntArray(gw * gh)
        for (s in 0 until gw * gh) {
            if (comp[s] != -1) continue
            val id = compLabel.size
            val lab = label[s]
            var sp2 = 0
            stack[sp2++] = s; comp[s] = id
            var area = 0
            while (sp2 > 0) {
                val c = stack[--sp2]
                area++
                val x = c % gw; val y = c / gw
                if (x > 0 && comp[c - 1] == -1 && label[c - 1] == lab) { comp[c - 1] = id; stack[sp2++] = c - 1 }
                if (x < gw - 1 && comp[c + 1] == -1 && label[c + 1] == lab) { comp[c + 1] = id; stack[sp2++] = c + 1 }
                if (y > 0 && comp[c - gw] == -1 && label[c - gw] == lab) { comp[c - gw] = id; stack[sp2++] = c - gw }
                if (y < gh - 1 && comp[c + gw] == -1 && label[c + gw] == lab) { comp[c + gw] = id; stack[sp2++] = c + gw }
            }
            compLabel.add(lab); compArea.add(area)
        }
        val n0 = compLabel.size
        val keep = BooleanArray(n0) { compArea[it] >= minArea }
        if (keep.none { it }) keep[compArea.indices.maxByOrNull { compArea[it] }!!] = true
        // absorb dropped pixels into neighbouring kept components (iterative dilation)
        var pass = 0
        while (pass++ < 40) {
            var missing = 0
            val next = comp.copyOf()
            for (y in 0 until gh) for (x in 0 until gw) {
                val i = y * gw + x
                val c = comp[i]
                if (keep[c]) continue
                var nb = -1
                if (x > 0 && keep[comp[i - 1]]) nb = comp[i - 1]
                else if (x < gw - 1 && keep[comp[i + 1]]) nb = comp[i + 1]
                else if (y > 0 && keep[comp[i - gw]]) nb = comp[i - gw]
                else if (y < gh - 1 && keep[comp[i + gw]]) nb = comp[i + gw]
                if (nb >= 0) next[i] = nb else missing++
            }
            System.arraycopy(next, 0, comp, 0, comp.size)
            if (missing == 0) break
        }
        // recount areas of kept comps
        val area = IntArray(n0)
        for (c in comp) if (keep[c]) area[c]++

        fun compColor(c: Int): Int {
            val l = compLabel[c]
            return (0xFF shl 24) or (centers[l * 3].roundToInt().coerceIn(0, 255) shl 16) or
                (centers[l * 3 + 1].roundToInt().coerceIn(0, 255) shl 8) or centers[l * 3 + 2].roundToInt().coerceIn(0, 255)
        }
        fun compLum(c: Int): Float {
            val l = compLabel[c]
            return 0.299f * centers[l * 3] + 0.587f * centers[l * 3 + 1] + 0.114f * centers[l * 3 + 2]
        }
        val skip = BooleanArray(n0) { !keep[it] || area[it] == 0 || (skipNearWhite && compLum(it) > 246f) }
        // mark skipped comps in grid so they produce no runs
        val grid = IntArray(gw * gh) { val c = comp[it]; if (skip[c]) -1 else c }

        // 4. scanline runs over the whole grid (one pass for all regions)
        val th = FILL_ANGLE_DEG * PI / 180.0
        val dx = cos(th).toFloat(); val dy = sin(th).toFloat()
        val nx = -dy; val ny = dx
        val cornersU = floatArrayOf(0f, gw * dx, gh * dy, gw * dx + gh * dy)
        val cornersV = floatArrayOf(0f, gw * nx, gh * ny, gw * nx + gh * ny)
        val umin = cornersU.min(); val umax = cornersU.max()
        val vmin = cornersV.min(); val vmax = cornersV.max()
        val runs = ArrayList<Run>()
        val step = 0.7f
        var line = 0
        var v = vmin + spG * 0.5f
        while (v < vmax) {
            var cur = -1
            var start = 0f
            var lastIn = 0f
            var u = umin
            while (u <= umax) {
                val x = (u * dx + v * nx).toInt().let { if (u * dx + v * nx < 0f) -1 else it }
                val y = (u * dy + v * ny).toInt().let { if (u * dy + v * ny < 0f) -1 else it }
                val c = if (x in 0 until gw && y in 0 until gh) grid[y * gw + x] else -1
                if (c != cur) {
                    if (cur >= 0) runs.add(Run(cur, line, start, lastIn))
                    cur = c
                    start = u
                }
                if (c >= 0) lastIn = u + step
                u += step
            }
            if (cur >= 0) runs.add(Run(cur, line, start, lastIn))
            line++
            v += spG
        }

        // 5. group runs by component
        val perComp = arrayOfNulls<ArrayList<Int>>(n0)
        for ((i, r) in runs.withIndex()) {
            (perComp[r.comp] ?: ArrayList<Int>().also { perComp[r.comp] = it }).add(i)
        }
        val used = BooleanArray(runs.size)
        val order = (0 until n0).filter { perComp[it] != null }.sortedWith(
            compareByDescending<Int> { floor(ln(area[it].toDouble()) / ln(2.0)) }.thenByDescending { compLum(it) },
        )
        val inset = width * 0.25f
        val reach = spG * 3f
        val out = ArrayList<Stroke>()
        val pts = FloatArraySink()
        for (c in order) {
            val list = perComp[c]!!
            val color = compColor(c)
            for (pi in list.indices) {
                if (used[list[pi]]) continue
                pts.clear()
                var cur = pi
                var forward = true
                while (true) {
                    val r = runs[list[cur]]
                    used[list[cur]] = true
                    var a = r.u0; var b = r.u1
                    if (b - a > 2 * inset + 1f) { a += inset / 2f; b -= inset / 2f }
                    else { val m = (a + b) / 2f; a = m - 0.4f; b = m + 0.4f }
                    val vv = vmin + spG * (r.line + 0.5f)
                    val first = if (forward) a else b
                    val second = if (forward) b else a
                    pts.add((first * dx + vv * nx) * sx, (first * dy + vv * ny) * sy)
                    pts.add((second * dx + vv * nx) * sx, (second * dy + vv * ny) * sy)
                    val endU = second
                    // next line candidate
                    var q = cur + 1
                    while (q < list.size && runs[list[q]].line == r.line) q++
                    var best = -1
                    var bestD = reach
                    while (q < list.size && runs[list[q]].line == r.line + 1) {
                        val cand = runs[list[q]]
                        if (!used[list[q]]) {
                            val nearEnd = if (forward) cand.u1 else cand.u0
                            val d = abs(nearEnd - endU)
                            if (d < bestD) { bestD = d; best = q }
                        }
                        q++
                    }
                    if (best < 0) break
                    cur = best
                    forward = !forward
                }
                out.add(Stroke(pts.toArray(), color, width))
            }
        }
        return out
    }

    private class FloatArraySink {
        private var a = FloatArray(128)
        private var n = 0
        fun clear() { n = 0 }
        fun add(x: Float, y: Float) {
            if (n + 2 > a.size) a = a.copyOf(a.size * 2)
            a[n++] = x; a[n++] = y
        }
        fun toArray(): FloatArray = a.copyOf(n)
    }

    // ------------------------------------------------------------ helpers

    /** Box-average resample to gw x gh; returns interleaved r,g,b floats (alpha composited on white). */
    private fun resample(px: IntArray, w: Int, h: Int, gw: Int, gh: Int): FloatArray {
        val out = FloatArray(gw * gh * 3)
        for (gy in 0 until gh) {
            val y0 = (gy.toLong() * h / gh).toInt()
            val y1 = max(y0 + 1, ((gy + 1).toLong() * h / gh).toInt()).coerceAtMost(h)
            for (gx in 0 until gw) {
                val x0 = (gx.toLong() * w / gw).toInt()
                val x1 = max(x0 + 1, ((gx + 1).toLong() * w / gw).toInt()).coerceAtMost(w)
                var r = 0f; var g = 0f; var b = 0f
                for (y in y0 until y1) for (x in x0 until x1) {
                    val c = px[y * w + x]
                    val a = (c ushr 24) / 255f
                    r += ((c shr 16) and 255) * a + 255 * (1 - a)
                    g += ((c shr 8) and 255) * a + 255 * (1 - a)
                    b += (c and 255) * a + 255 * (1 - a)
                }
                val cnt = ((y1 - y0) * (x1 - x0)).toFloat()
                val o = (gy * gw + gx) * 3
                out[o] = r / cnt; out[o + 1] = g / cnt; out[o + 2] = b / cnt
            }
        }
        return out
    }

    /** Deterministic k-means++ on a subsample; returns (centers r,g,b interleaved, label per pixel). */
    private fun kmeans(rgb: FloatArray, gw: Int, gh: Int, kReq: Int): Pair<FloatArray, IntArray> {
        val n = gw * gh
        val stride = max(1, n / 12000)
        val m = (n + stride - 1) / stride
        val tr = FloatArray(m * 3)
        for (i in 0 until m) { val s = i * stride * 3; tr[i * 3] = rgb[s]; tr[i * 3 + 1] = rgb[s + 1]; tr[i * 3 + 2] = rgb[s + 2] }
        val rnd = java.util.Random(1234)
        val k = min(kReq, m)
        val cen = FloatArray(k * 3)
        val d2 = FloatArray(m) { Float.MAX_VALUE }
        var first = rnd.nextInt(m)
        for (c in 0 until k) {
            if (c > 0) {
                var total = 0.0
                for (i in 0 until m) total += d2[i]
                var pick = rnd.nextDouble() * total
                first = m - 1
                for (i in 0 until m) { pick -= d2[i]; if (pick <= 0) { first = i; break } }
            }
            cen[c * 3] = tr[first * 3]; cen[c * 3 + 1] = tr[first * 3 + 1]; cen[c * 3 + 2] = tr[first * 3 + 2]
            for (i in 0 until m) {
                val dr = tr[i * 3] - cen[c * 3]; val dg = tr[i * 3 + 1] - cen[c * 3 + 1]; val db = tr[i * 3 + 2] - cen[c * 3 + 2]
                val d = dr * dr + dg * dg + db * db
                if (d < d2[i]) d2[i] = d
            }
        }
        val sum = FloatArray(k * 3)
        val cnt = IntArray(k)
        for (iter in 0 until 10) {
            java.util.Arrays.fill(sum, 0f); java.util.Arrays.fill(cnt, 0)
            for (i in 0 until m) {
                val c = nearest(cen, k, tr[i * 3], tr[i * 3 + 1], tr[i * 3 + 2])
                sum[c * 3] += tr[i * 3]; sum[c * 3 + 1] += tr[i * 3 + 1]; sum[c * 3 + 2] += tr[i * 3 + 2]; cnt[c]++
            }
            for (c in 0 until k) if (cnt[c] > 0) {
                cen[c * 3] = sum[c * 3] / cnt[c]; cen[c * 3 + 1] = sum[c * 3 + 1] / cnt[c]; cen[c * 3 + 2] = sum[c * 3 + 2] / cnt[c]
            }
        }
        val lab = IntArray(n) { nearest(cen, k, rgb[it * 3], rgb[it * 3 + 1], rgb[it * 3 + 2]) }
        return cen to lab
    }

    private fun nearest(cen: FloatArray, k: Int, r: Float, g: Float, b: Float): Int {
        var best = 0
        var bd = Float.MAX_VALUE
        for (c in 0 until k) {
            val dr = r - cen[c * 3]; val dg = g - cen[c * 3 + 1]; val db = b - cen[c * 3 + 2]
            val d = dr * dr + dg * dg + db * db
            if (d < bd) { bd = d; best = c }
        }
        return best
    }

    /** 3x3 majority filter to remove single-pixel label noise. */
    private fun modeFilter(lab: IntArray, w: Int, h: Int, k: Int): IntArray {
        val out = lab.copyOf()
        val cnt = IntArray(k)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            java.util.Arrays.fill(cnt, 0)
            for (j in -1..1) for (i in -1..1) cnt[lab[(y + j) * w + x + i]]++
            var best = lab[y * w + x]
            for (c in 0 until k) if (cnt[c] > cnt[best]) best = c
            if (cnt[best] >= 5) out[y * w + x] = best
        }
        return out
    }
}
