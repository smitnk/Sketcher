package com.smitnk.sketcher.image.pipeline

import com.smitnk.sketcher.image.BrushType
import com.smitnk.sketcher.image.Stroke
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Artist-style shading: hatches / scribbles only the darkest tonal regions of the
 * subject (nested tone bands), never the flat background. Pure Kotlin.
 */
object ShadingGenerator {

    private const val COLOR = 0xFF3A3A3A.toInt()
    private const val HARD_CAP = 1500

    private class Piece(val region: Int, val band: Int, val order: Int, val stroke: Stroke)

    /**
     * @param cartoon true for flat-colour cartoon art: only cel-shadow regions (a darker tone of the same hue
     * adjacent to a larger region) are shaded; if none qualify the result is empty.
     */
    fun generate(
        argb: IntArray, w: Int, h: Int, settings: PipelineSettings, ink: FloatArray?,
        cartoon: Boolean = false,
    ): List<Stroke> {
        if (settings.shading == ShadingLevel.NONE || w < 8 || h < 8 || argb.size < w * h) return emptyList()
        val n = w * h
        val long = max(w, h)
        val level = settings.shading

        // ---- tone map -------------------------------------------------------
        val lum = FloatArray(n)
        for (i in 0 until n) {
            val c = argb[i]
            lum[i] = (0.299f * ((c shr 16) and 255) + 0.587f * ((c shr 8) and 255) + 0.114f * (c and 255)) / 255f
        }
        if (cartoon) return generateCartoon(argb, w, h, settings, ink, lum)
        // heavier smoothing keeps band contours organic instead of speckled
        val tone = boxBlur(boxBlur(boxBlur(lum, w, h, max(3, long / 110)), w, h, max(3, long / 110)), w, h, max(2, long / 160))

        // ---- subject estimation --------------------------------------------
        val subject: ByteArray? = if (settings.ignoreBackground) estimateSubject(lum, w, h, ink) else null

        // ---- thresholds from the histogram of eligible pixels ----------------
        val pct = when (level) { ShadingLevel.LIGHT -> 0.12f; ShadingLevel.MEDIUM -> 0.25f; else -> 0.40f }
        val hist = IntArray(256)
        var cnt = 0
        for (i in 0 until n) {
            if (subject != null && subject[i].toInt() == 0) continue
            hist[(tone[i] * 255f + 0.5f).toInt().coerceIn(0, 255)]++; cnt++
        }
        if (cnt < n / 50) return emptyList()
        fun thr(p: Float): Float {
            val target = p * cnt
            var acc = 0
            for (b in 0..255) { acc += hist[b]; if (acc >= target) return (b + 1) / 255f }
            return 1f
        }
        val fracs = if (level == ShadingLevel.LIGHT) floatArrayOf(pct, pct * 0.45f)
        else floatArrayOf(pct, pct * 0.55f, pct * 0.25f)
        val ts = FloatArray(fracs.size) { thr(fracs[it]) }
        // Very bright images: never shade anything lighter than mid-grey-ish.
        for (k in ts.indices) ts[k] = min(ts[k], 0.82f - 0.08f * k)

        // ---- band masks (nested, cleaned) -----------------------------------
        val mr = max(1, long / 260)
        val minArea = max(40, (long * 0.022f * long * 0.022f).toInt())
        val skip = inkSkipMask(lum, w, h, ink)
        val masks = ArrayList<ByteArray>()
        var prev: ByteArray? = null
        for (k in ts.indices) {
            val m = ByteArray(n)
            for (i in 0 until n) {
                if (tone[i] > ts[k]) continue
                val inSubject = subject == null || subject[i].toInt() != 0
                // At HEAVY the background may only receive the very darkest band, lightly.
                if (inSubject || (level == ShadingLevel.HEAVY && k == ts.size - 1)) m[i] = 1
            }
            for (i in 0 until n) if (skip[i].toInt() != 0) m[i] = 0
            var c = morph(morph(m, w, h, mr, true), w, h, mr, false)   // open
            c = morph(morph(c, w, h, mr + 1, false), w, h, mr + 1, true) // close
            c = smoothMask(c, w, h, max(2, long / 80))
            for (i in 0 until n) if (skip[i].toInt() != 0) c[i] = 0
            if (prev != null) for (i in 0 until n) if (prev[i].toInt() == 0) c[i] = 0
            removeSmall(c, w, h, minArea)
            masks.add(c); prev = c
        }
        val regionLabels = label(masks[0], w, h)

        // ---- stroke generation (adaptive spacing to respect the cap) --------
        val cap = min(HARD_CAP, settings.maxStrokes)
        var spacing = max(max(4f, long * 0.0068f), 2.2f * hatchWidth(long))
        var pieces: List<Piece> = emptyList()
        for (attempt in 0 until 8) {
            pieces = build(masks, regionLabels, w, h, long, spacing, settings.shadingStyle)
            if (pieces.size <= cap) break
            spacing *= 1.22f
        }
        val sorted = pieces.sortedWith(compareBy<Piece>({ it.region }, { it.band }, { it.order }))
        return sorted.take(cap).map { it.stroke }
    }

    // ===================== hatch / scribble =====================================

    private fun hatchWidth(long: Int) = (1.4f * (long / 1024f).coerceIn(0.7f, 2f)).coerceIn(1.2f, 1.6f * 2f)

    private fun build(
        masks: List<ByteArray>, labels: IntArray, w: Int, h: Int, long: Int, spacing: Float, style: ShadingStyle,
    ): List<Piece> {
        val rnd = Random(1234567L + w * 31L + h)
        val width = hatchWidth(long)
        val out = ArrayList<Piece>()
        val minLen = max(7f, long * 0.012f)
        val maxLen = long * (if (style == ShadingStyle.SCRIBBLE) 0.14f else 0.075f)
        for (k in masks.indices) {
            val dirs: List<Pair<Float, Float>> = when (style) { // angle deg, phase
                ShadingStyle.HATCH -> if (k == 0) listOf(45f to 0f) else if (k == 1) listOf(45f to 0.5f) else listOf(52f to 0.25f)
                ShadingStyle.CROSS_HATCH -> when (k) { 0 -> listOf(45f to 0f); 1 -> listOf(45f to 0.5f); else -> listOf(135f to 0f) }
                ShadingStyle.SCRIBBLE -> if (k == 0) listOf(40f to 0f) else if (k == 1) listOf(130f to 0.3f) else listOf(80f to 0.6f)
            }
            val sp = if (style == ShadingStyle.SCRIBBLE) spacing * 1.1f else spacing
            for ((ang, phase) in dirs) {
                lines(masks[k], labels, w, h, ang, sp, phase, k, rnd, width, minLen, maxLen, style == ShadingStyle.SCRIBBLE, out)
            }
        }
        return out
    }

    private fun lines(
        mask: ByteArray, labels: IntArray, w: Int, h: Int, angDeg: Float, spacing: Float, phase: Float, band: Int,
        rnd: Random, width: Float, minLen: Float, maxLen: Float, scribble: Boolean, out: MutableList<Piece>,
    ) {
        val a0 = Math.toRadians(angDeg.toDouble())
        val diag = sqrt((w * w + h * h).toDouble()).toFloat()
        val cx = w / 2f; val cy = h / 2f
        val nl = (diag / spacing).toInt() + 2
        var order = 0
        val gapTol = 3
        for (li in -nl / 2..nl / 2) {
            val ja = a0 + Math.toRadians((rnd.nextFloat() - 0.5) * 6.0)
            val ux = cos(ja).toFloat(); val uy = sin(ja).toFloat()
            val nx = -sin(a0).toFloat(); val ny = cos(a0).toFloat()
            val off = (li + phase + (rnd.nextFloat() - 0.5f) * 0.25f) * spacing
            val bx = cx + nx * off; val by = cy + ny * off
            var t = -diag / 2f
            var runStart = Float.NaN
            var lastIn = Float.NaN
            val tEnd = diag / 2f
            while (t <= tEnd) {
                val px = (bx + ux * t + 0.5f).toInt(); val py = (by + uy * t + 0.5f).toInt()
                val inside = px in 0 until w && py in 0 until h && mask[py * w + px].toInt() != 0
                if (inside) {
                    if (runStart.isNaN()) runStart = t
                    lastIn = t
                } else if (!runStart.isNaN() && t - lastIn > gapTol) {
                    emitRun(runStart, lastIn, bx, by, ux, uy, labels, w, h, band, spacing, rnd, width, minLen, maxLen, scribble, order, out)
                    order++
                    runStart = Float.NaN
                }
                t += 1f
            }
            if (!runStart.isNaN()) { emitRun(runStart, lastIn, bx, by, ux, uy, labels, w, h, band, spacing, rnd, width, minLen, maxLen, scribble, order, out); order++ }
        }
    }

    private fun emitRun(
        t0: Float, t1: Float, bx: Float, by: Float, ux: Float, uy: Float, labels: IntArray, w: Int, h: Int, band: Int,
        spacing: Float, rnd: Random, width: Float, minLen: Float, maxLen: Float, scribble: Boolean, order: Int, out: MutableList<Piece>,
    ) {
        if (t1 - t0 < minLen) return
        var s = t0
        while (s < t1) {
            var e = min(t1, s + maxLen * (0.7f + 0.3f * rnd.nextFloat()))
            if (t1 - e < minLen * 0.8f) e = t1
            // taper: pull ends in by a random amount
            // feather: ends that sit on a band edge get a random, sometimes long, pull-in so edges look drawn
            val feather = spacing * 1.8f
            val fa = if (s <= t0) rnd.nextFloat() * rnd.nextFloat() * feather else 0f
            val fb = if (e >= t1) rnd.nextFloat() * rnd.nextFloat() * feather else 0f
            val a = s + rnd.nextFloat() * min(3f, (e - s) * 0.1f) + (if (s > t0) 1f else 0f) + fa
            val b = e - rnd.nextFloat() * min(3f, (e - s) * 0.1f) - (if (e < t1) 1f else 0f) - fb
            if (b - a >= minLen) {
                val mx = (bx + ux * (a + b) / 2 + 0.5f).toInt().coerceIn(0, w - 1)
                val my = (by + uy * (a + b) / 2 + 0.5f).toInt().coerceIn(0, h - 1)
                val region = labels[my * w + mx]
                val nx = -uy; val ny = ux
                val pts: FloatArray
                if (!scribble) {
                    val bow = (rnd.nextFloat() - 0.5f) * 1.6f
                    val m = (a + b) / 2
                    pts = floatArrayOf(
                        bx + ux * a, by + uy * a,
                        bx + ux * m + nx * bow, by + uy * m + ny * bow,
                        bx + ux * b, by + uy * b,
                    )
                } else {
                    val len = b - a
                    val amp = spacing * 0.62f
                    val step = spacing * (0.45f + 0.25f * rnd.nextFloat())
                    val list = ArrayList<Float>()
                    var tt = 0f
                    var side = if (rnd.nextBoolean()) 1f else -1f
                    while (tt <= len) {
                        val env = min(1f, min(tt, len - tt) / (amp * 1.5f) + 0.35f)
                        val across = side * amp * env * (0.65f + 0.35f * rnd.nextFloat())
                        val along = a + tt + (rnd.nextFloat() - 0.5f) * step * 0.4f
                        list.add(bx + ux * along + nx * across); list.add(by + uy * along + ny * across)
                        side = -side
                        tt += step
                    }
                    pts = list.toFloatArray()
                }
                out.add(Piece(region, band, order, Stroke(pts, COLOR, width * (0.9f + 0.2f * rnd.nextFloat()), BrushType.HATCH, 0.8f + 0.2f * rnd.nextFloat())))
            }
            s = e + (if (scribble) 0f else rnd.nextFloat() * 3f)
        }
    }

    // ===================== ink density / smoothing ================================

    /** Cells where existing line art is dense or sits right on a line: hatching there only adds mud. */
    private fun inkSkipMask(lum: FloatArray, w: Int, h: Int, ink: FloatArray?): ByteArray {
        val n = w * h
        val long = max(w, h)
        val src = inkBinary(lum, w, h, ink)
        val dens = boxBlur(src, w, h, max(3, long / 30))
        val out = ByteArray(n) { if (dens[it] > 0.27f) 1 else 0 }
        // keep a small clearance around lines so hatching doesn't collide with the outline
        // light clearance only around strong ink (soft AI ink maps would otherwise erase everything)
        if (ink != null && ink.size >= n) {
            val strong = ByteArray(n) { if (ink[it] > 0.7f) 1 else 0 }
            val near = morph(strong, w, h, 1, false)
            for (i in 0 until n) if (near[i].toInt() != 0) out[i] = 1
        }
        return out
    }

    private fun inkBinary(lum: FloatArray, w: Int, h: Int, ink: FloatArray?): FloatArray {
        val n = w * h
        if (ink != null && ink.size >= n) return FloatArray(n) { if (ink[it] > 0.35f) 1f else 0f }
        val l = boxBlur(lum, w, h, 1)
        val e = FloatArray(n)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val g = abs(l[i + 1] - l[i - 1]) + abs(l[i + w] - l[i - w])
            e[i] = if (g > 0.12f) 1f else 0f
        }
        return e
    }

    /** Blur + threshold: rounds corners so contours are smooth curves rather than blocky polygons. */
    private fun smoothMask(m: ByteArray, w: Int, h: Int, r: Int): ByteArray {
        var f = FloatArray(m.size) { m[it].toFloat() }
        f = boxBlur(boxBlur(f, w, h, r), w, h, r)
        return ByteArray(m.size) { if (f[it] > 0.5f) 1 else 0 }
    }

    // ===================== cartoon (cel shading aware) ============================

    private fun generateCartoon(
        argb: IntArray, w: Int, h: Int, settings: PipelineSettings, ink: FloatArray?, lum: FloatArray,
    ): List<Stroke> {
        val n = w * h
        val long = max(w, h)
        val minArea = max(60, (long * 0.025f * long * 0.025f).toInt())
        val lab = IntArray(n) { -1 }
        val areas = ArrayList<Int>()
        val sumR = ArrayList<Long>(); val sumG = ArrayList<Long>(); val sumB = ArrayList<Long>()
        val q = IntArray(n)
        val tol = 12
        var k = 0
        for (s0 in 0 until n) {
            if (lab[s0] >= 0) continue
            val c0 = argb[s0]
            val r0 = (c0 shr 16) and 255; val g0 = (c0 shr 8) and 255; val b0 = c0 and 255
            var qh = 0; var qt = 0
            q[qt++] = s0; lab[s0] = k
            var sr = 0L; var sg = 0L; var sb = 0L
            while (qh < qt) {
                val i = q[qh++]
                val c = argb[i]
                sr += (c shr 16) and 255; sg += (c shr 8) and 255; sb += c and 255
                val x = i % w; val y = i / w
                for (d in 0 until 4) {
                    val j = when (d) { 0 -> if (x > 0) i - 1 else -1; 1 -> if (x < w - 1) i + 1 else -1; 2 -> if (y > 0) i - w else -1; else -> if (y < h - 1) i + w else -1 }
                    if (j < 0 || lab[j] >= 0) continue
                    val cj = argb[j]
                    if (abs(((cj shr 16) and 255) - r0) <= tol && abs(((cj shr 8) and 255) - g0) <= tol && abs((cj and 255) - b0) <= tol) {
                        lab[j] = k; q[qt++] = j
                    }
                }
            }
            areas.add(qt); sumR.add(sr); sumG.add(sg); sumB.add(sb); k++
        }
        // per-region HSL
        val hue = FloatArray(k); val sat = FloatArray(k); val light = FloatArray(k)
        for (r in 0 until k) {
            val a = areas[r].toFloat()
            val rr = sumR[r] / a / 255f; val gg = sumG[r] / a / 255f; val bb = sumB[r] / a / 255f
            val mx = max(rr, max(gg, bb)); val mn = min(rr, min(gg, bb))
            light[r] = (mx + mn) / 2f
            val d = mx - mn
            sat[r] = if (d < 1e-4f) 0f else d / (1f - abs(2f * light[r] - 1f)).coerceAtLeast(1e-3f)
            hue[r] = if (d < 1e-4f) 0f else 60f * when (mx) {
                rr -> (((gg - bb) / d) % 6f + 6f) % 6f
                gg -> (bb - rr) / d + 2f
                else -> (rr - gg) / d + 4f
            }
        }
        fun sameHue(a: Int, b: Int): Boolean {
            val lowA = sat[a] < 0.15f; val lowB = sat[b] < 0.15f
            if (lowA && lowB) return true
            if (lowA || lowB) return false
            var dh = abs(hue[a] - hue[b]); if (dh > 180f) dh = 360f - dh
            return dh < 28f
        }
        // candidate shadow regions: sizeable, not ink-black
        val cand = BooleanArray(k) { areas[it] >= minArea && areas[it] < n * 0.35f && light[it] > 0.15f }
        val isShadow = BooleanArray(k)
        val adj = HashMap<Int, IntArray>()  // candidate -> adjacency counts keyed later
        val reach = max(2, long / 120)
        val adjCount = HashMap<Long, Int>()
        for (y in 0 until h) for (x in 0 until w) {
            val a = lab[y * w + x]
            if (!cand[a]) continue
            for (d in 0 until 4) {
                val dx = if (d == 0) 1 else if (d == 1) -1 else 0
                val dy = if (d == 2) 1 else if (d == 3) -1 else 0
                var xx = x; var yy = y
                var t = 0
                while (t < reach) {
                    xx += dx; yy += dy; t++
                    if (xx !in 0 until w || yy !in 0 until h) break
                    val b = lab[yy * w + xx]
                    if (b == a) continue
                    if (areas[b] < 3) continue   // antialias dust
                    // thin dark line between: keep looking past it
                    if (light[b] <= 0.2f || areas[b] < minArea / 4) continue
                    val key = a.toLong() * k + b
                    adjCount[key] = (adjCount[key] ?: 0) + 1
                    break
                }
            }
        }
        val perim = IntArray(k)
        for (y in 0 until h) for (x in 0 until w) {
            val a = lab[y * w + x]
            if (cand[a] && ((x > 0 && lab[y * w + x - 1] != a) || (x < w - 1 && lab[y * w + x + 1] != a) ||
                    (y > 0 && lab[(y - 1) * w + x] != a) || (y < h - 1 && lab[(y + 1) * w + x] != a))) perim[a]++
        }
        for ((key, cnt) in adjCount) {
            val a = (key / k).toInt(); val b = (key % k).toInt()
            if (areas[b] <= areas[a] * 1.3f) continue
            if (cnt < max(8, perim[a] / 6)) continue
            if (!sameHue(a, b)) continue
            if (light[a] < light[b] * 0.85f && light[a] > light[b] * 0.35f) isShadow[a] = true
        }
        val mask = ByteArray(n)
        var any = false
        for (i in 0 until n) if (isShadow[lab[i]]) { mask[i] = 1; any = true }
        if (!any) return emptyList()
        var c = morph(morph(mask, w, h, max(1, long / 260), true), w, h, max(1, long / 260), false)
        c = smoothMask(c, w, h, max(2, long / 120))
        // keep clear of the outline
        val src = inkBinary(lum, w, h, ink)
        val near = morph(ByteArray(n) { if (src[it] > 0f) 1 else 0 }, w, h, max(1, long / 300), false)
        for (i in 0 until n) if (near[i].toInt() != 0) c[i] = 0
        removeSmall(c, w, h, minArea / 2)
        var left = false; for (v in c) if (v.toInt() != 0) { left = true; break }
        if (!left) return emptyList()
        val labels = label(c, w, h)
        val cap = min(HARD_CAP, settings.maxStrokes)
        var spacing = max(max(4f, long * 0.0068f), 2.2f * hatchWidth(long))
        var pieces: List<Piece> = emptyList()
        for (attempt in 0 until 8) {
            pieces = build(listOf(c), labels, w, h, long, spacing, settings.shadingStyle)
            if (pieces.size <= cap) break
            spacing *= 1.22f
        }
        return pieces.sortedWith(compareBy<Piece>({ it.region }, { it.band }, { it.order })).take(cap).map { it.stroke }
    }

    // ===================== subject / background =================================

    private fun estimateSubject(lum: FloatArray, w: Int, h: Int, ink: FloatArray?): ByteArray {
        val n = w * h
        val long = max(w, h)
        val src: FloatArray = if (ink != null && ink.size >= n) FloatArray(n) { if (ink[it] > 0.35f) 1f else 0f }
        else {
            val l = boxBlur(lum, w, h, 1)
            val e = FloatArray(n)
            for (y in 1 until h - 1) for (x in 1 until w - 1) {
                val i = y * w + x
                val g = abs(l[i + 1] - l[i - 1]) + abs(l[i + w] - l[i - w])
                e[i] = if (g > 0.12f) 1f else 0f
            }
            e
        }
        val act = boxBlur(src, w, h, max(2, long / 55))
        var m = ByteArray(n) { if (act[it] > 0.045f) 1 else 0 }
        m = morph(morph(m, w, h, max(2, long / 40), true), w, h, max(2, long / 40), false)   // close
        m = morph(morph(m, w, h, max(1, long / 90), false), w, h, max(1, long / 90), true)   // open
        // fill holes: whatever background is not reachable from the border belongs to the subject
        val reach = ByteArray(n)
        val q = IntArray(n); var qh = 0; var qt = 0
        fun push(i: Int) { if (m[i].toInt() == 0 && reach[i].toInt() == 0) { reach[i] = 1; q[qt++] = i } }
        for (x in 0 until w) { push(x); push((h - 1) * w + x) }
        for (y in 0 until h) { push(y * w); push(y * w + w - 1) }
        while (qh < qt) {
            val i = q[qh++]; val x = i % w; val y = i / w
            if (x > 0) push(i - 1); if (x < w - 1) push(i + 1); if (y > 0) push(i - w); if (y < h - 1) push(i + w)
        }
        val res = ByteArray(n) { if (reach[it].toInt() == 0) 1 else 0 }
        var c = 0; for (v in res) c += v
        if (c < n * 0.02) java.util.Arrays.fill(res, 1.toByte())
        return res
    }

    // ===================== image helpers =========================================

    private fun boxBlur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(src.size)
        for (y in 0 until h) {
            val row = y * w
            var sum = 0f; var c = 0
            for (x in 0..min(r, w - 1)) { sum += src[row + x]; c++ }
            for (x in 0 until w) {
                tmp[row + x] = sum / c
                val add = x + r + 1; val rem = x - r
                if (add < w) { sum += src[row + add]; c++ }
                if (rem >= 0) { sum -= src[row + rem]; c-- }
            }
        }
        val out = FloatArray(src.size)
        for (x in 0 until w) {
            var sum = 0f; var c = 0
            for (y in 0..min(r, h - 1)) { sum += tmp[y * w + x]; c++ }
            for (y in 0 until h) {
                out[y * w + x] = sum / c
                val add = y + r + 1; val rem = y - r
                if (add < h) { sum += tmp[add * w + x]; c++ }
                if (rem >= 0) { sum -= tmp[rem * w + x]; c-- }
            }
        }
        return out
    }

    /** Square-element erode (all) or dilate (any) with radius r; out-of-image cells are ignored. */
    private fun morph(src: ByteArray, w: Int, h: Int, r: Int, erode: Boolean): ByteArray {
        val tmp = ByteArray(src.size)
        for (y in 0 until h) {
            val row = y * w
            var sum = 0; var c = 0
            for (x in 0..min(r, w - 1)) { sum += src[row + x]; c++ }
            for (x in 0 until w) {
                tmp[row + x] = (if (erode) sum == c else sum > 0).let { if (it) 1 else 0 }.toByte()
                val add = x + r + 1; val rem = x - r
                if (add < w) { sum += src[row + add]; c++ }
                if (rem >= 0) { sum -= src[row + rem]; c-- }
            }
        }
        val out = ByteArray(src.size)
        for (x in 0 until w) {
            var sum = 0; var c = 0
            for (y in 0..min(r, h - 1)) { sum += tmp[y * w + x]; c++ }
            for (y in 0 until h) {
                out[y * w + x] = (if (erode) sum == c else sum > 0).let { if (it) 1 else 0 }.toByte()
                val add = y + r + 1; val rem = y - r
                if (add < h) { sum += tmp[add * w + x]; c++ }
                if (rem >= 0) { sum -= tmp[rem * w + x]; c-- }
            }
        }
        return out
    }

    /** 4-connected component labels (1..k, 0 = none). */
    private fun label(mask: ByteArray, w: Int, h: Int): IntArray {
        val lab = IntArray(mask.size)
        val q = IntArray(mask.size)
        var next = 0
        for (s in mask.indices) {
            if (mask[s].toInt() == 0 || lab[s] != 0) continue
            next++
            var qh = 0; var qt = 0
            q[qt++] = s; lab[s] = next
            while (qh < qt) {
                val i = q[qh++]; val x = i % w; val y = i / w
                if (x > 0 && mask[i - 1].toInt() != 0 && lab[i - 1] == 0) { lab[i - 1] = next; q[qt++] = i - 1 }
                if (x < w - 1 && mask[i + 1].toInt() != 0 && lab[i + 1] == 0) { lab[i + 1] = next; q[qt++] = i + 1 }
                if (y > 0 && mask[i - w].toInt() != 0 && lab[i - w] == 0) { lab[i - w] = next; q[qt++] = i - w }
                if (y < h - 1 && mask[i + w].toInt() != 0 && lab[i + w] == 0) { lab[i + w] = next; q[qt++] = i + w }
            }
        }
        return lab
    }

    /** Clears connected components smaller than minArea (in place). */
    private fun removeSmall(mask: ByteArray, w: Int, h: Int, minArea: Int) {
        val lab = label(mask, w, h)
        var k = 0; for (v in lab) if (v > k) k = v
        val area = IntArray(k + 1)
        for (v in lab) area[v]++
        for (i in mask.indices) if (lab[i] != 0 && area[lab[i]] < minArea) mask[i] = 0
    }
}
