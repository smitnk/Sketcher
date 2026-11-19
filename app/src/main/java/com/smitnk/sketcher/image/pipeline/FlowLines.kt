// Ported from SSARCandy/Coherent-Line-Drawing (MIT, (c) 2019 HSU,SHU-HSUAN)
// Paper: Kang, Lee, Chui. "Coherent Line Drawing". NPAR 2007.
// Pure Kotlin, zero-model: Edge Tangent Flow (ETF) + flow-based Difference-of-Gaussians (FDoG).
// GPU note (future): the repo's webgl/index.html is a one-fragment-shader-per-stage port of the same maths
// (FS_SOBEL, FS_ETF_INIT, FS_ETF_REFINE ping-pong, FS_DOG, FS_FDOG, FS_THRESH, FS_COMBINE). A GLES 3.0 / Vulkan
// compute path can reuse those shaders 1:1; see docs/pipeline.md.
package com.smitnk.sketcher.image.pipeline

import java.util.stream.IntStream
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

object FlowLines {

    /** Defaults follow the C++ implementation (sigma_c 1.0, sigma_m 3.0, rho 0.997, tau 0.8, ETF kernel 5, 3 ETF passes). */
    data class Params(
        val etfKernel: Int = 5,
        val etfIterations: Int = 3,
        val sigmaC: Float = 1.0f,
        val sigmaM: Float = 3.0f,
        val rho: Float = 0.997f,
        val tau: Float = 0.8f,
        /** CLD iterations: >1 superimposes the previous lines on the image and filters again (paper section 4). */
        val iterations: Int = 1,
        /** Long side the filters run at (larger inputs are downscaled, the ink is upsampled back). */
        val maxSide: Int = 768,
    )

    /** UI "detail" 0..1 -> parameters. 0.5 gives the C++ defaults; more detail = higher tau and finer sigma_c. */
    fun paramsForDetail(detail: Float): Params {
        val d = detail.coerceIn(0f, 1f)
        return Params(tau = 0.70f + 0.30f * d, sigmaC = 1.4f - 0.8f * d)
    }

    private const val SIGMA_RATIO = 1.6f
    private const val GAUSS_CUT = 0.001

    private class Field(val w: Int, val h: Int, val tx: FloatArray, val ty: FloatArray, val mag: FloatArray)

    private fun par(n: Int, body: (Int) -> Unit) {
        IntStream.range(0, n).parallel().forEach { body(it) }
    }

    /** Ink map (0..1, [w]x[h]); 1 = line. Runs at most [Params.maxSide] on the long side and upsamples bilinearly. */
    fun inkMap(argb: IntArray, w: Int, h: Int, p: Params = Params()): FloatArray {
        val s = min(1f, p.maxSide.toFloat() / max(w, h))
        val gw = max(8, (w * s).roundToInt()); val gh = max(8, (h * s).roundToInt())
        val gray = if (gw == w && gh == h) luma(argb, w, h) else resizeBilinear(luma(argb, w, h), w, h, gw, gh)
        val small = inkSmall(gray, gw, gh, p)
        return if (gw == w && gh == h) small else resizeBilinear(small, gw, gh, w, h)
    }

    /** The binary line map as ink at working size (no resampling). Exposed for tests/tuning. */
    fun inkSmall(grayIn: FloatArray, w: Int, h: Int, p: Params): FloatArray {
        val gray = grayIn.copyOf()
        stretch(gray)
        val field = etf(gray, w, h, p)
        var src = gray
        var ink = FloatArray(w * h)
        for (it in 0 until max(1, p.iterations)) {
            val dog = gradientDoG(src, field, p.rho, p.sigmaC)
            val f = flowDoG(dog, field, p.sigmaM)
            ink = threshold(f, p.tau)
            if (it + 1 < p.iterations) src = combine(grayIn, ink, w, h)
        }
        return ink
    }

    // ---------------------------------------------------------------- ETF

    private fun etf(gray: FloatArray, w: Int, h: Int, p: Params): Field {
        var tx = FloatArray(w * h); var ty = FloatArray(w * h)
        val mag = FloatArray(w * h)
        // 5x5 Sobel (cv::Sobel ksize=5): smooth [1 4 6 4 1] x derivative [-1 -2 0 2 1], separable
        val sm = floatArrayOf(1f, 4f, 6f, 4f, 1f); val dv = floatArrayOf(-1f, -2f, 0f, 2f, 1f)
        val smX = FloatArray(w * h); val dvX = FloatArray(w * h)
        par(h) { y ->
            for (x in 0 until w) {
                var a = 0f; var b = 0f
                for (k in -2..2) {
                    val v = gray[y * w + (x + k).coerceIn(0, w - 1)]
                    a += sm[k + 2] * v; b += dv[k + 2] * v
                }
                smX[y * w + x] = a; dvX[y * w + x] = b
            }
        }
        var mn = Float.MAX_VALUE; var mx = 0f
        val gxA = FloatArray(w * h); val gyA = FloatArray(w * h)
        par(h) { y ->
            for (x in 0 until w) {
                var gx = 0f; var gy = 0f
                for (k in -2..2) {
                    val yy = (y + k).coerceIn(0, h - 1) * w + x
                    gx += sm[k + 2] * dvX[yy]
                    gy += dv[k + 2] * smX[yy]
                }
                val i = y * w + x
                gxA[i] = gx; gyA[i] = gy
                mag[i] = sqrt(gx * gx + gy * gy)
            }
        }
        for (v in mag) { if (v < mn) mn = v; if (v > mx) mx = v }
        val inv = 1f / max(mx - mn, 1e-12f)
        for (i in mag.indices) mag[i] = (mag[i] - mn) * inv
        // tangent = gradient rotated by 90 degrees
        for (i in 0 until w * h) {
            val gx = gxA[i]; val gy = gyA[i]
            val l = sqrt(gx * gx + gy * gy)
            if (l > 1e-9f) { tx[i] = -gy / l; ty[i] = gx / l }
        }
        // wm = (1 + tanh(dm)) / 2 for dm in [-1, 1]: lookup table
        val lut = FloatArray(LUT_N + 1) { i -> val d = (i.toFloat() / LUT_N) * 2f - 1f; 0.5f * (1f + tanhF(d)) }
        val k = p.etfKernel
        val offs = ArrayList<Int>(); val offX = ArrayList<Int>(); val offY = ArrayList<Int>()
        for (dy in -k..k) for (dx in -k..k) if (dx * dx + dy * dy < k * k) { offX.add(dx); offY.add(dy) }
        val ox = offX.toIntArray(); val oy = offY.toIntArray(); val n = ox.size
        offs.clear()
        for (iter in 0 until p.etfIterations) {
            val nx = FloatArray(w * h); val ny = FloatArray(w * h)
            val cx = tx; val cy = ty
            par(h) { y ->
                for (x in 0 until w) {
                    val i = y * w + x
                    val ax = cx[i]; val ay = cy[i]; val am = mag[i]
                    var sx = 0f; var sy = 0f
                    for (j in 0 until n) {
                        val xx = x + ox[j]; val yy = y + oy[j]
                        if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
                        val q = yy * w + xx
                        val dot = ax * cx[q] + ay * cy[q]          // phi * w_d = sign(dot) * |dot| = dot
                        val wm = lut[((mag[q] - am + 1f) * (LUT_N / 2f)).toInt()]
                        val f = dot * wm
                        sx += f * cx[q]; sy += f * cy[q]
                    }
                    val l = sqrt(sx * sx + sy * sy)
                    if (l > 1e-9f) { nx[i] = sx / l; ny[i] = sy / l } else { nx[i] = ax; ny[i] = ay }
                }
            }
            tx = nx; ty = ny
        }
        return Field(w, h, tx, ty, mag)
    }

    private const val LUT_N = 1024
    private fun tanhF(x: Float): Float { val e = exp(2.0 * x); return ((e - 1.0) / (e + 1.0)).toFloat() }

    // ---------------------------------------------------------------- DoG across the flow, FDoG along it

    private fun gaussHalf(sigma: Float): FloatArray {
        var n = 0
        val c = 1.0 / (sqrt(2.0 * Math.PI) * sigma)
        fun g(i: Int) = c * exp(-(i.toDouble() * i) / (2.0 * sigma * sigma))
        while (g(n) >= GAUSS_CUT) n++
        return FloatArray(n + 1) { g(it).toFloat() }
    }

    private fun bil(a: FloatArray, w: Int, h: Int, x: Float, y: Float): Float {
        val xf = x.coerceIn(0f, w - 1f); val yf = y.coerceIn(0f, h - 1f)
        val x0 = min(xf.toInt(), w - 2).coerceAtLeast(0); val y0 = min(yf.toInt(), h - 2).coerceAtLeast(0)
        val x1 = min(x0 + 1, w - 1); val y1 = min(y0 + 1, h - 1)
        val fx = xf - x0; val fy = yf - y0
        val top = a[y0 * w + x0] * (1 - fx) + a[y0 * w + x1] * fx
        val bot = a[y1 * w + x0] * (1 - fx) + a[y1 * w + x1] * fx
        return top * (1 - fy) + bot * fy
    }

    private fun gradientDoG(src: FloatArray, f: Field, rho: Float, sigmaC: Float): FloatArray {
        val w = f.w; val h = f.h
        val gc = gaussHalf(sigmaC); val gs = gaussHalf(SIGMA_RATIO * sigmaC)
        val kernel = gs.size - 1
        val dst = FloatArray(w * h)
        par(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                // gradient direction = perpendicular of the tangent
                val gx = f.ty[i]; val gy = -f.tx[i]
                if (gx == 0f && gy == 0f) continue
                var cA = 0f; var sA = 0f; var cW = 0f; var sW = 0f
                for (st in -kernel..kernel) {
                    val px = x + gx * st; val py = y + gy * st
                    if (px < 0f || px > w - 1f || py < 0f || py > h - 1f) continue
                    val v = bil(src, w, h, px, py)
                    val a = abs(st)
                    val wc = if (a < gc.size) gc[a] else 0f
                    val ws = gs[a]
                    cA += v * wc; sA += v * ws; cW += wc; sW += ws
                }
                if (cW > 0f && sW > 0f) dst[i] = cA / cW - rho * sA / sW
            }
        }
        return dst
    }

    private fun flowDoG(dog: FloatArray, f: Field, sigmaM: Float): FloatArray {
        val w = f.w; val h = f.h
        val g = gaussHalf(sigmaM)
        val half = g.size - 1
        val dst = FloatArray(w * h)
        par(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                var acc = -g[0] * dog[i]; var wacc = -g[0]
                for (dir in 0..1) {
                    var px = x.toFloat(); var py = y.toFloat()
                    var dx = if (dir == 0) f.tx[i] else -f.tx[i]
                    var dy = if (dir == 0) f.ty[i] else -f.ty[i]
                    var step = 0
                    while (step < half) {
                        if (px < 0f || px > w - 1f || py < 0f || py > h - 1f) break
                        if (dx == 0f && dy == 0f) break
                        val wgt = g[step]
                        acc += bil(dog, w, h, px, py) * wgt; wacc += wgt
                        // advance one pixel along the flow, bilinearly sampled, sign-aligned with the walk direction
                        val x0 = min(px.toInt(), w - 2).coerceAtLeast(0); val y0 = min(py.toInt(), h - 2).coerceAtLeast(0)
                        val fx = (px - x0).coerceIn(0f, 1f); val fy = (py - y0).coerceIn(0f, 1f)
                        var sx = 0f; var sy = 0f
                        for (c in 0..3) {
                            val xx = min(x0 + (c and 1), w - 1); val yy = min(y0 + (c shr 1), h - 1)
                            val wt = (if (c and 1 == 0) 1 - fx else fx) * (if (c shr 1 == 0) 1 - fy else fy)
                            val q = yy * w + xx
                            val tx = f.tx[q]; val ty = f.ty[q]
                            val sgn = if (tx * dx + ty * dy >= 0f) wt else -wt
                            sx += sgn * tx; sy += sgn * ty
                        }
                        val l = sqrt(sx * sx + sy * sy)
                        if (l < 1e-6f) break
                        dx = sx / l; dy = sy / l
                        px += dx; py += dy
                        step++
                    }
                }
                val m = acc / wacc
                dst[i] = if (m > 0f) 1f else 1f + tanhF(m)
            }
        }
        // min-max normalise (cv::NORM_MINMAX)
        var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE
        for (v in dst) { if (v < mn) mn = v; if (v > mx) mx = v }
        val inv = 1f / max(mx - mn, 1e-12f)
        for (i in dst.indices) dst[i] = (dst[i] - mn) * inv
        return dst
    }

    /** Line where value < tau; one-pixel soft edge so the later upsample / vectoriser sees anti-aliased ink. */
    private fun threshold(f: FloatArray, tau: Float): FloatArray {
        val soft = 0.06f
        return FloatArray(f.size) { ((tau - f[it]) / soft + 0.5f).coerceIn(0f, 1f) }
    }

    /** CLD iteration: stamp black line pixels onto the original and blur 3x3 (C++ combineImage). */
    private fun combine(orig: FloatArray, ink: FloatArray, w: Int, h: Int): FloatArray {
        val c = FloatArray(w * h) { if (ink[it] > 0.5f) 0f else orig[it] }
        val out = FloatArray(w * h)
        val k = floatArrayOf(1f, 2f, 1f)
        val tmp = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var a = 0f
            for (d in -1..1) a += k[d + 1] * c[y * w + (x + d).coerceIn(0, w - 1)]
            tmp[y * w + x] = a / 4f
        }
        for (y in 0 until h) for (x in 0 until w) {
            var a = 0f
            for (d in -1..1) a += k[d + 1] * tmp[(y + d).coerceIn(0, h - 1) * w + x]
            out[y * w + x] = a / 4f
        }
        val r = out
        stretch(r)
        return r
    }

    // ---------------------------------------------------------------- helpers

    private fun stretch(a: FloatArray) {
        var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE
        for (v in a) { if (v < mn) mn = v; if (v > mx) mx = v }
        val inv = 1f / max(mx - mn, 1e-6f)
        for (i in a.indices) a[i] = (a[i] - mn) * inv
    }

    private fun luma(argb: IntArray, w: Int, h: Int): FloatArray = FloatArray(w * h) {
        val c = argb[it]
        (0.299f * ((c shr 16) and 255) + 0.587f * ((c shr 8) and 255) + 0.114f * (c and 255)) / 255f
    }

    private fun resizeBilinear(src: FloatArray, sw: Int, sh: Int, dw: Int, dh: Int): FloatArray {
        val out = FloatArray(dw * dh)
        val fx = sw.toFloat() / dw; val fy = sh.toFloat() / dh
        // area-average when shrinking so thin detail is not aliased away
        val shrink = fx > 1.5f || fy > 1.5f
        par(dh) { y ->
            for (x in 0 until dw) {
                if (!shrink) {
                    out[y * dw + x] = bil(src, sw, sh, (x + 0.5f) * fx - 0.5f, (y + 0.5f) * fy - 0.5f)
                } else {
                    val x0 = (x * fx).toInt().coerceIn(0, sw - 1); val x1 = max(x0 + 1, ((x + 1) * fx).toInt()).coerceAtMost(sw)
                    val y0 = (y * fy).toInt().coerceIn(0, sh - 1); val y1 = max(y0 + 1, ((y + 1) * fy).toInt()).coerceAtMost(sh)
                    var a = 0f
                    for (yy in y0 until y1) for (xx in x0 until x1) a += src[yy * sw + xx]
                    out[y * dw + x] = a / ((x1 - x0) * (y1 - y0))
                }
            }
        }
        return out
    }
}
