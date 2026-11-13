package com.smitnk.sketcher.image

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.cbrt
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
        darken: Float = 0.62f,
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
                if (lum > 115f) { val k = 115f / lum; rr *= k; gg *= k; bb *= k }
                val col = (0xFF shl 24) or (rr.roundToInt().coerceIn(0, 255) shl 16) or
                    (gg.roundToInt().coerceIn(0, 255) shl 8) or bb.roundToInt().coerceIn(0, 255)
                Stroke(p, col, s.width)
            }
        }
    }

    // ------------------------------------------------------------ fill strokes

    private class Run(val comp: Int, val line: Int, val u0: Float, val u1: Float, val r: Float, val g: Float, val b: Float)

    /** Tunables (grid / output units). */
    private const val ERODE_WIDTHS = 0.6f      // keep fills this many stroke widths away from paper / ink lines
    private const val MERGE_DE = 7.5f          // adjacent regions closer than this (Lab dE) become one gradient region
    private const val MERGE_SMALL_DE = 16f     // small regions merge with their nearest neighbour up to this
    private const val STROKE_DE = 1.8f
    private const val SEG_DE = 1.5f           // runs through gradient regions are cut when colour drifts this far         // zig-zag chain breaks when its colour drifts more than this

    private class Lab(val l: FloatArray, val a: FloatArray, val b: FloatArray)

    /**
     * Fill strokes for the photo, in [outW]x[outH] coordinates (same space as the outline).
     * [colors] 6..16, [spacing] distance between scribble lines in output pixels.
     * Quantise in Lab (over-segmented), smooth, merge close neighbours into gradient regions whose
     * strokes each carry their own sampled colour, erode away from paper / ink lines.
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
        val n = gw * gh
        val sx = outW.toFloat() / gw
        val sy = outH.toFloat() / gh
        val sp = max(1.5f, spacing)
        val spG = sp / sx
        val width = sp * 1.3f

        // 1. resample to grid (box average + min luminance for ink detection)
        val minLum = FloatArray(n)
        val rgb = resample(pixels, imgW, imgH, gw, gh, minLum)
        val lab = toLab(rgb, n)
        // 2. over-segmenting k-means in Lab, then smooth the label map
        val kFine = max(k0, min(28, k0 * 2))
        val (centers, label0) = kmeans(lab, n, kFine)
        val k = centers.size / 3
        val label = smoothLabels(label0, gw, gh, k)

        // 3. connected components, drop tiny and absorb them
        val minArea = max(12, (n * 0.0005f).toInt())
        val comp = IntArray(n) { -1 }
        val compArea = ArrayList<Int>()
        val stack = IntArray(n)
        for (s in 0 until n) {
            if (comp[s] != -1) continue
            val id = compArea.size
            val lab0 = label[s]
            var sp2 = 0
            stack[sp2++] = s; comp[s] = id
            var area = 0
            while (sp2 > 0) {
                val c = stack[--sp2]
                area++
                val x = c % gw; val y = c / gw
                if (x > 0 && comp[c - 1] == -1 && label[c - 1] == lab0) { comp[c - 1] = id; stack[sp2++] = c - 1 }
                if (x < gw - 1 && comp[c + 1] == -1 && label[c + 1] == lab0) { comp[c + 1] = id; stack[sp2++] = c + 1 }
                if (y > 0 && comp[c - gw] == -1 && label[c - gw] == lab0) { comp[c - gw] = id; stack[sp2++] = c - gw }
                if (y < gh - 1 && comp[c + gw] == -1 && label[c + gw] == lab0) { comp[c + gw] = id; stack[sp2++] = c + gw }
            }
            compArea.add(area)
        }
        val n0 = compArea.size
        val keep = BooleanArray(n0) { compArea[it] >= minArea }
        if (keep.none { it }) keep[compArea.indices.maxByOrNull { compArea[it] }!!] = true
        var pass = 0
        while (pass++ < 40) {
            var missing = 0
            val next = comp.copyOf()
            for (y in 0 until gh) for (x in 0 until gw) {
                val i = y * gw + x
                if (keep[comp[i]]) continue
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

        // 4. merge adjacent regions with similar colour (gradients become one region)
        mergeRegions(comp, lab, gw, gh, n0, keep)
        val area = IntArray(n0)
        val sL = DoubleArray(n0); val sA = DoubleArray(n0); val sB = DoubleArray(n0)
        for (i in 0 until n) { val c = comp[i]; area[c]++; sL[c] += lab.l[i]; sA[c] += lab.a[i]; sB[c] += lab.b[i] }
        fun isPaper(c: Int): Boolean {
            if (area[c] == 0) return false
            val l = sL[c] / area[c]; val a = sA[c] / area[c]; val b = sB[c] / area[c]
            return l > 93.0 && sqrt(a * a + b * b) < 7.0
        }
        val q = DoubleArray(n0)
        for (i in 0 until n) { val c = comp[i]; q[c] += (lab.l[i] * lab.l[i] + lab.a[i] * lab.a[i] + lab.b[i] * lab.b[i]).toDouble() }
        val gradient = BooleanArray(n0) {
            if (area[it] == 0) false else {
                val m2 = (sL[it] * sL[it] + sA[it] * sA[it] + sB[it] * sB[it]) / area[it]
                sqrt(max(0.0, (q[it] - m2) / area[it])) > 3.0
            }
        }
        val skip = BooleanArray(n0) { area[it] == 0 || (skipNearWhite && isPaper(it)) }

        // 5. barrier = paper regions + thin ink lines; keep fills away from it
        val barrier = BooleanArray(n) { skip[comp[it]] }
        val ink = BooleanArray(n) { minLum[it] < 75f }
        val solid = open(ink, gw, gh, 1)
        val thin = BooleanArray(n) { ink[it] && !solid[it] }
        dropSmall(thin, gw, gh, 30, stack)
        for (i in 0 until n) if (thin[i]) barrier[i] = true
        val dist = distanceTo(barrier, gw, gh)
        val er = ERODE_WIDTHS * width / sx
        val grid = IntArray(n) { if (barrier[it] || dist[it] <= er) -1 else comp[it] }

        // 6. scanline runs over the whole grid (one pass for all regions)
        val th = FILL_ANGLE_DEG * PI / 180.0
        val dx = cos(th).toFloat(); val dy = sin(th).toFloat()
        val nx = -dy; val ny = dx
        val cornersU = floatArrayOf(0f, gw * dx, gh * dy, gw * dx + gh * dy)
        val cornersV = floatArrayOf(0f, gw * nx, gh * ny, gw * nx + gh * ny)
        val umin = cornersU.min(); val umax = cornersU.max()
        val vmin = cornersV.min(); val vmax = cornersV.max()
        val minRun = 0.8f * width / sx
        val minBreak = 2.2f * width / sx
        val runs = ArrayList<Run>()
        val step = 0.7f
        var line = 0
        var v = vmin + spG * 0.5f
        while (v < vmax) {
            var cur = -1
            var start = 0f
            var lastIn = 0f
            var sr = 0f; var sg = 0f; var sb = 0f; var cnt = 0
            var ml = 0f; var ma = 0f; var mb = 0f
            var startBreak = false
            fun flush(endBreak: Boolean) {
                if (cur >= 0 && cnt > 0 && (lastIn - start >= minRun || startBreak || endBreak))
                    runs.add(Run(cur, line, start, lastIn, sr / cnt, sg / cnt, sb / cnt))
            }
            var u = umin
            while (u <= umax) {
                val fx = u * dx + v * nx; val fy = u * dy + v * ny
                val x = if (fx < 0f) -1 else fx.toInt()
                val y = if (fy < 0f) -1 else fy.toInt()
                val c = if (x in 0 until gw && y in 0 until gh) grid[y * gw + x] else -1
                var brk = false
                if (c >= 0 && c == cur && gradient[c] && cnt > 0 && u - start >= minBreak) {
                    val i = y * gw + x
                    val dl = lab.l[i] - ml / cnt; val da = lab.a[i] - ma / cnt; val db = lab.b[i] - mb / cnt
                    if (dl * dl + da * da + db * db > SEG_DE * SEG_DE) brk = true
                }
                if (c != cur || brk) {
                    flush(brk)
                    startBreak = brk
                    cur = c; start = u; sr = 0f; sg = 0f; sb = 0f; cnt = 0; ml = 0f; ma = 0f; mb = 0f
                }
                if (c >= 0) {
                    val i = y * gw + x
                    val o = i * 3
                    sr += rgb[o]; sg += rgb[o + 1]; sb += rgb[o + 2]; cnt++
                    ml += lab.l[i]; ma += lab.a[i]; mb += lab.b[i]
                    lastIn = u + step
                }
                u += step
            }
            flush(false)
            line++
            v += spG
        }

        // 7. group runs by component, chain into zig-zag strokes with per-stroke sampled colour
        val perComp = arrayOfNulls<ArrayList<Int>>(n0)
        for ((i, r) in runs.withIndex()) {
            (perComp[r.comp] ?: ArrayList<Int>().also { perComp[r.comp] = it }).add(i)
        }
        val used = BooleanArray(runs.size)
        fun compLum(c: Int): Double = if (area[c] > 0) sL[c] / area[c] else 0.0
        val order = (0 until n0).filter { perComp[it] != null }.sortedWith(
            compareByDescending<Int> { floor(ln(area[it].toDouble()) / ln(2.0)) }.thenByDescending { compLum(it) },
        )
        val inset = width * 0.25f
        val reach = spG * 3f
        val out = ArrayList<Stroke>()
        val pts = FloatArraySink()
        val tmp = FloatArray(3)
        for (c in order) {
            val list = perComp[c]!!
            for (pi in list.indices) {
                if (used[list[pi]]) continue
                pts.clear()
                var cur = pi
                var forward = true
                var wr = 0f; var wg = 0f; var wb = 0f; var wsum = 0f
                while (true) {
                    val r = runs[list[cur]]
                    used[list[cur]] = true
                    val rl = r.u1 - r.u0
                    wr += r.r * rl; wg += r.g * rl; wb += r.b * rl; wsum += rl
                    var a = r.u0; var b = r.u1
                    if (b - a > 2 * inset + 1f) { a += inset / 2f; b -= inset / 2f }
                    else { val m = (a + b) / 2f; a = m - 0.4f; b = m + 0.4f }
                    val vv = vmin + spG * (r.line + 0.5f)
                    val first = if (forward) a else b
                    val second = if (forward) b else a
                    pts.add((first * dx + vv * nx) * sx, (first * dy + vv * ny) * sy)
                    pts.add((second * dx + vv * nx) * sx, (second * dy + vv * ny) * sy)
                    val endU = second
                    var q = cur + 1
                    while (q < list.size && runs[list[q]].line == r.line) q++
                    var best = -1
                    var bestD = reach
                    while (q < list.size && runs[list[q]].line == r.line + 1) {
                        val cand = runs[list[q]]
                        if (!used[list[q]]) {
                            val nearEnd = if (forward) cand.u1 else cand.u0
                            val d = abs(nearEnd - endU)
                            if (d < bestD && deltaE(wr / wsum, wg / wsum, wb / wsum, cand.r, cand.g, cand.b, tmp) < STROKE_DE) {
                                bestD = d; best = q
                            }
                        }
                        q++
                    }
                    if (best < 0) break
                    cur = best
                    forward = !forward
                }
                val cr = (wr / wsum); val cg = (wg / wsum); val cb = (wb / wsum)
                if (skipNearWhite && isNearWhite(cr, cg, cb, tmp)) continue
                val color = (0xFF shl 24) or (cr.roundToInt().coerceIn(0, 255) shl 16) or
                    (cg.roundToInt().coerceIn(0, 255) shl 8) or cb.roundToInt().coerceIn(0, 255)
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

    private val linTable = FloatArray(256) { val c = it / 255.0; (if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)).toFloat() }

    private val fTable = FloatArray(4097) { val t = it / 4096f; if (t > 0.008856f) cbrt(t) else 7.787f * t + 16f / 116f }

    private fun labF(t: Float): Float {
        val p = t.coerceIn(0f, 1.2f) * 4096f
        val i = p.toInt().coerceAtMost(4095)
        val fr = p - i
        return if (p >= 4096f) cbrt(t) else fTable[i] + (fTable[i + 1] - fTable[i]) * fr
    }

    /** Removes 8-connected components of [m] smaller than [minArea] pixels. */
    private fun dropSmall(m: BooleanArray, w: Int, h: Int, minArea: Int, stack: IntArray) {
        val seen = BooleanArray(w * h)
        val members = IntArray(w * h)
        for (s0 in m.indices) {
            if (!m[s0] || seen[s0]) continue
            var sp = 0; var cnt = 0
            stack[sp++] = s0; seen[s0] = true
            while (sp > 0) {
                val c = stack[--sp]
                members[cnt++] = c
                val x = c % w; val y = c / w
                for (j in -1..1) for (i in -1..1) {
                    val xx = x + i; val yy = y + j
                    if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
                    val q = yy * w + xx
                    if (m[q] && !seen[q]) { seen[q] = true; stack[sp++] = q }
                }
            }
            if (cnt < minArea) for (i in 0 until cnt) m[members[i]] = false
        }
    }

    /** sRGB (0..255 floats) -> CIE Lab, returned in three arrays. */
    private fun rgbToLab(r: Float, g: Float, b: Float, out: FloatArray) {
        val lr = linTable[r.roundToInt().coerceIn(0, 255)]
        val lg = linTable[g.roundToInt().coerceIn(0, 255)]
        val lb = linTable[b.roundToInt().coerceIn(0, 255)]
        val x = (0.4124f * lr + 0.3576f * lg + 0.1805f * lb) / 0.95047f
        val y = 0.2126f * lr + 0.7152f * lg + 0.0722f * lb
        val z = (0.0193f * lr + 0.1192f * lg + 0.9505f * lb) / 1.08883f
        val fx = labF(x); val fy = labF(y); val fz = labF(z)
        out[0] = 116f * fy - 16f; out[1] = 500f * (fx - fy); out[2] = 200f * (fy - fz)
    }

    private fun toLab(rgb: FloatArray, n: Int): Lab {
        val l = FloatArray(n); val a = FloatArray(n); val b = FloatArray(n)
        val t = FloatArray(3)
        for (i in 0 until n) {
            rgbToLab(rgb[i * 3], rgb[i * 3 + 1], rgb[i * 3 + 2], t)
            l[i] = t[0]; a[i] = t[1]; b[i] = t[2]
        }
        return Lab(l, a, b)
    }

    private fun deltaE(r1: Float, g1: Float, b1: Float, r2: Float, g2: Float, b2: Float, tmp: FloatArray): Float {
        rgbToLab(r1, g1, b1, tmp)
        val l1 = tmp[0]; val a1 = tmp[1]; val bb1 = tmp[2]
        rgbToLab(r2, g2, b2, tmp)
        val dl = l1 - tmp[0]; val da = a1 - tmp[1]; val db = bb1 - tmp[2]
        return sqrt(dl * dl + da * da + db * db)
    }

    private fun isNearWhite(r: Float, g: Float, b: Float, tmp: FloatArray): Boolean {
        rgbToLab(r, g, b, tmp)
        return tmp[0] > 94f && sqrt(tmp[1] * tmp[1] + tmp[2] * tmp[2]) < 6f
    }

    /** Box-average resample to gw x gh; returns interleaved r,g,b floats (alpha composited on white). */
    private fun resample(px: IntArray, w: Int, h: Int, gw: Int, gh: Int, minLum: FloatArray): FloatArray {
        val out = FloatArray(gw * gh * 3)
        for (gy in 0 until gh) {
            val y0 = (gy.toLong() * h / gh).toInt()
            val y1 = max(y0 + 1, ((gy + 1).toLong() * h / gh).toInt()).coerceAtMost(h)
            for (gx in 0 until gw) {
                val x0 = (gx.toLong() * w / gw).toInt()
                val x1 = max(x0 + 1, ((gx + 1).toLong() * w / gw).toInt()).coerceAtMost(w)
                var r = 0f; var g = 0f; var b = 0f
                var mn = 255f
                for (y in y0 until y1) for (x in x0 until x1) {
                    val c = px[y * w + x]
                    val a = (c ushr 24) / 255f
                    val cr = ((c shr 16) and 255) * a + 255 * (1 - a)
                    val cg = ((c shr 8) and 255) * a + 255 * (1 - a)
                    val cb = (c and 255) * a + 255 * (1 - a)
                    r += cr; g += cg; b += cb
                    val lum = 0.299f * cr + 0.587f * cg + 0.114f * cb
                    if (lum < mn) mn = lum
                }
                val cnt = ((y1 - y0) * (x1 - x0)).toFloat()
                val o = (gy * gw + gx) * 3
                out[o] = r / cnt; out[o + 1] = g / cnt; out[o + 2] = b / cnt
                minLum[gy * gw + gx] = mn
            }
        }
        return out
    }

    /** Deterministic k-means++ in Lab on a subsample; returns (centers L,a,b interleaved, label per pixel). */
    private fun kmeans(lab: Lab, n: Int, kReq: Int): Pair<FloatArray, IntArray> {
        val stride = max(1, n / 12000)
        val m = (n + stride - 1) / stride
        val tr = FloatArray(m * 3)
        for (i in 0 until m) { val s = i * stride; tr[i * 3] = lab.l[s]; tr[i * 3 + 1] = lab.a[s]; tr[i * 3 + 2] = lab.b[s] }
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
        val labels = IntArray(n) { nearest(cen, k, lab.l[it], lab.a[it], lab.b[it]) }
        return cen to labels
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

    /** Several majority-filter passes (3x3 x3, then 5x5) so region borders come out smooth. */
    private fun smoothLabels(lab0: IntArray, w: Int, h: Int, k: Int): IntArray {
        var cur = lab0
        for (p in 0 until 4) cur = majority(cur, w, h, k, if (p < 3) 1 else 2)
        return cur
    }

    private fun majority(lab: IntArray, w: Int, h: Int, k: Int, rad: Int): IntArray {
        val out = lab.copyOf()
        val cnt = IntArray(k)
        val need = if (rad == 1) 4 else 11
        for (y in rad until h - rad) for (x in rad until w - rad) {
            val ci = y * w + x
            val c0 = lab[ci]
            if (lab[ci - 1] == c0 && lab[ci + 1] == c0 && lab[ci - w] == c0 && lab[ci + w] == c0 &&
                lab[ci - w - 1] == c0 && lab[ci - w + 1] == c0 && lab[ci + w - 1] == c0 && lab[ci + w + 1] == c0) continue
            for (j in -rad..rad) for (i in -rad..rad) cnt[lab[(y + j) * w + x + i]]++
            var best = lab[y * w + x]
            var bc = cnt[best]
            for (j in -rad..rad) for (i in -rad..rad) { val l = lab[(y + j) * w + x + i]; if (cnt[l] > bc) { bc = cnt[l]; best = l } }
            if (bc >= need) out[y * w + x] = best
            for (j in -rad..rad) for (i in -rad..rad) cnt[lab[(y + j) * w + x + i]] = 0
        }
        return out
    }

    /** Greedy merge of touching regions whose mean Lab colours are close. Rewrites [comp] to root ids. */
    private fun mergeRegions(comp: IntArray, lab: Lab, w: Int, h: Int, n0: Int, keep: BooleanArray) {
        val area = DoubleArray(n0)
        val sl = DoubleArray(n0); val sa = DoubleArray(n0); val sb = DoubleArray(n0)
        for (i in comp.indices) { val c = comp[i]; area[c] += 1.0; sl[c] += lab.l[i]; sa[c] += lab.a[i]; sb[c] += lab.b[i] }
        val adj = Array(n0) { HashMap<Int, Int>() }
        fun link(a: Int, b: Int) {
            if (a == b) return
            adj[a].merge(b, 1, Int::plus); adj[b].merge(a, 1, Int::plus)
        }
        for (y in 0 until h) for (x in 0 until w) {
            val c = comp[y * w + x]
            if (x < w - 1) link(c, comp[y * w + x + 1])
            if (y < h - 1) link(c, comp[(y + 1) * w + x])
        }
        val parent = IntArray(n0) { it }
        val alive = BooleanArray(n0) { area[it] > 0 }
        val smallArea = w * h * 0.004
        fun de(a: Int, b: Int): Double {
            val dl = sl[a] / area[a] - sl[b] / area[b]
            val da = sa[a] / area[a] - sa[b] / area[b]
            val db = sb[a] / area[a] - sb[b] / area[b]
            return sqrt(dl * dl + da * da + db * db)
        }
        while (true) {
            var ba = -1; var bb = -1; var bs = Double.MAX_VALUE
            for (a in 0 until n0) {
                if (!alive[a]) continue
                for ((b, cnt) in adj[a]) {
                    if (b <= a || cnt < 3) continue
                    val d = de(a, b)
                    val lim = if (area[a] < smallArea || area[b] < smallArea) MERGE_SMALL_DE else MERGE_DE
                    if (d < lim && d < bs) { bs = d; ba = a; bb = b }
                }
            }
            if (ba < 0) break
            // merge bb into ba
            parent[bb] = ba; alive[bb] = false
            area[ba] += area[bb]; sl[ba] += sl[bb]; sa[ba] += sa[bb]; sb[ba] += sb[bb]
            for ((k, cnt) in adj[bb]) {
                adj[k].remove(bb)
                if (k == ba) continue
                adj[ba].merge(k, cnt, Int::plus); adj[k].merge(ba, cnt, Int::plus)
            }
            adj[ba].remove(bb)
            adj[bb].clear()
        }
        fun root(c: Int): Int { var r = c; while (parent[r] != r) r = parent[r]; return r }
        for (i in comp.indices) comp[i] = root(comp[i])
    }

    /** Morphological opening with a (2r+1) square; removes structures thinner than that. */
    private fun open(m: BooleanArray, w: Int, h: Int, r: Int): BooleanArray = dilate(erode(m, w, h, r), w, h, r)

    private fun erode(m: BooleanArray, w: Int, h: Int, r: Int): BooleanArray = sepFilter(m, w, h, r, true)
    private fun dilate(m: BooleanArray, w: Int, h: Int, r: Int): BooleanArray = sepFilter(m, w, h, r, false)

    private fun sepFilter(m: BooleanArray, w: Int, h: Int, r: Int, isErode: Boolean): BooleanArray {
        val tmp = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var v = isErode
            for (i in -r..r) {
                val xx = x + i
                val b = if (xx < 0 || xx >= w) isErode else m[y * w + xx]
                if (isErode) { if (!b) { v = false; break } } else if (b) { v = true; break }
            }
            tmp[y * w + x] = v
        }
        val out = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var v = isErode
            for (j in -r..r) {
                val yy = y + j
                val b = if (yy < 0 || yy >= h) isErode else tmp[yy * w + x]
                if (isErode) { if (!b) { v = false; break } } else if (b) { v = true; break }
            }
            out[y * w + x] = v
        }
        return out
    }

    /** Chamfer distance (grid pixels) from every pixel to the nearest barrier pixel. */
    private fun distanceTo(barrier: BooleanArray, w: Int, h: Int): FloatArray {
        val inf = 1e9f
        val d = FloatArray(w * h) { if (barrier[it]) 0f else inf }
        val d2 = 1.4142f
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            var v = d[i]
            if (x > 0) v = min(v, d[i - 1] + 1f)
            if (y > 0) {
                v = min(v, d[i - w] + 1f)
                if (x > 0) v = min(v, d[i - w - 1] + d2)
                if (x < w - 1) v = min(v, d[i - w + 1] + d2)
            }
            d[i] = v
        }
        for (y in h - 1 downTo 0) for (x in w - 1 downTo 0) {
            val i = y * w + x
            var v = d[i]
            if (x < w - 1) v = min(v, d[i + 1] + 1f)
            if (y < h - 1) {
                v = min(v, d[i + w] + 1f)
                if (x < w - 1) v = min(v, d[i + w + 1] + d2)
                if (x > 0) v = min(v, d[i + w - 1] + d2)
            }
            d[i] = v
        }
        return d
    }
}
