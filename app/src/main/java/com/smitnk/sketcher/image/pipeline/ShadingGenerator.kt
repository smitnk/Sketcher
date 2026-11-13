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

    fun generate(argb: IntArray, w: Int, h: Int, settings: PipelineSettings, ink: FloatArray?): List<Stroke> {
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
        val tone = boxBlur(boxBlur(lum, w, h, max(2, long / 220)), w, h, max(2, long / 220))

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
            var c = morph(morph(m, w, h, mr, true), w, h, mr, false)   // open
            c = morph(morph(c, w, h, mr + 1, false), w, h, mr + 1, true) // close
            if (prev != null) for (i in 0 until n) if (prev[i].toInt() == 0) c[i] = 0
            removeSmall(c, w, h, minArea)
            masks.add(c); prev = c
        }
        val regionLabels = label(masks[0], w, h)

        // ---- stroke generation (adaptive spacing to respect the cap) --------
        val cap = min(HARD_CAP, settings.maxStrokes)
        var spacing = max(4f, long * 0.0068f)
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

    private fun build(
        masks: List<ByteArray>, labels: IntArray, w: Int, h: Int, long: Int, spacing: Float, style: ShadingStyle,
    ): List<Piece> {
        val rnd = Random(1234567L + w * 31L + h)
        val width = (1.4f * (long / 1024f).coerceIn(0.7f, 2f)).coerceIn(1.2f, 1.6f * 2f)
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
            val a = s + rnd.nextFloat() * min(3f, (e - s) * 0.1f) + (if (s > t0) 1f else 0f)
            val b = e - rnd.nextFloat() * min(3f, (e - s) * 0.1f) - (if (e < t1) 1f else 0f)
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
