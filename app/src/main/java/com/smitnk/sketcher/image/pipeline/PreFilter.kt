package com.smitnk.sketcher.image.pipeline

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Edge-preserving pre-smoothing of fur / grass / foliage style texture, applied before the line-art model so it
 * sees texture as flat tone and keeps real contours. Pure Kotlin, ~100 ms at 640 px.
 */
object PreFilter {
    private const val MAX_SIDE = 640

    /** @param strength 0..1 (0 = untouched). Returns a new ARGB array of the same size. */
    fun smoothTextures(argb: IntArray, w: Int, h: Int, strength: Float): IntArray {
        val s = strength.coerceIn(0f, 1f)
        if (s <= 0.01f || w < 8 || h < 8) return argb
        val long = max(w, h)
        if (long <= MAX_SIDE) return core(argb, w, h, s)
        val f = MAX_SIDE.toFloat() / long
        val sw = max(8, (w * f).roundToInt()); val sh = max(8, (h * f).roundToInt())
        val small = core(resize(argb, w, h, sw, sh), sw, sh, s)
        return resize(small, sw, sh, w, h)
    }

    private fun core(src: IntArray, w: Int, h: Int, s: Float): IntArray {
        val n = w * h
        val r = FloatArray(n); val g = FloatArray(n); val b = FloatArray(n); val l = FloatArray(n)
        for (i in 0 until n) {
            val p = src[i]
            r[i] = ((p shr 16) and 255).toFloat(); g[i] = ((p shr 8) and 255).toFloat(); b[i] = (p and 255).toFloat()
            l[i] = 0.299f * r[i] + 0.587f * g[i] + 0.114f * b[i]
        }
        val mask = textureMask(l, w, h, s)
        // edge-preserving smoothing: iterated bilateral; range sigma grows with strength
        val sr = 50f + 60f * s
        val iters = 2 + (8f * s).toInt()
        var cr = r; var cg = g; var cb = b
        for (it in 0 until iters) {
            val o = bilateral(cr, cg, cb, mask, w, h, 2, sr)
            cr = o[0]; cg = o[1]; cb = o[2]
        }
        val out = IntArray(n)
        for (i in 0 until n) {
            val m = mask[i]
            val a = src[i] ushr 24
            val rr = (r[i] + (cr[i] - r[i]) * m).roundToInt().coerceIn(0, 255)
            val gg = (g[i] + (cg[i] - g[i]) * m).roundToInt().coerceIn(0, 255)
            val bb = (b[i] + (cb[i] - b[i]) * m).roundToInt().coerceIn(0, 255)
            out[i] = (a shl 24) or (rr shl 16) or (gg shl 8) or bb
        }
        return out
    }

    /**
     * Texture = lots of gradient energy whose sign keeps flipping (|mean grad| << mean |grad|) in a window.
     * A real contour has a one-signed gradient, so it scores low and is left alone.
     */
    private fun textureMask(l: FloatArray, w: Int, h: Int, s: Float): FloatArray {
        val n = w * h
        val gx = FloatArray(n); val gy = FloatArray(n); val ax = FloatArray(n); val ay = FloatArray(n)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val dx = l[i + 1] - l[i - 1]; val dy = l[i + w] - l[i - w]
            gx[i] = dx; gy[i] = dy; ax[i] = abs(dx); ay[i] = abs(dy)
        }
        val rad = max(3, max(w, h) / 90)
        val mgx = box(gx, w, h, rad); val mgy = box(gy, w, h, rad)
        val max_ = box(ax, w, h, rad); val may = box(ay, w, h, rad)
        val energy = FloatArray(n); val incoh = FloatArray(n)
        for (i in 0 until n) {
            val e = max_[i] + may[i]
            energy[i] = e
            val c = Math.sqrt((mgx[i] * mgx[i] + mgy[i] * mgy[i]).toDouble()).toFloat()
            incoh[i] = if (e > 1e-3f) 1f - min(1f, c / e) else 0f
        }
        // energy must be high enough to be texture rather than flat sky; both gates are soft
        val e0 = 16f; val e1 = 32f
        val c0 = 0.55f - 0.1f * s; val c1 = c0 + 0.25f
        val m = FloatArray(n)
        for (i in 0 until n) {
            m[i] = smooth(energy[i], e0, e1) * smooth(incoh[i], c0, c1)
        }
        // small isolated clusters (eyes, mouth, buttons) are structure, only wide textured areas count
        val big = max(8, max(w, h) / 40)
        val dens = box(box(m, w, h, big), w, h, big)
        for (i in 0 until n) m[i] *= smooth(dens[i], 0.4f, 0.65f)
        return box(box(m, w, h, 2), w, h, 2)  // feather
    }

    private fun smooth(v: Float, a: Float, b: Float): Float {
        val t = ((v - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3 - 2 * t)
    }

    private fun box(a: FloatArray, w: Int, h: Int, rad: Int): FloatArray {
        val tmp = FloatArray(a.size); val out = FloatArray(a.size)
        val inv = 1f / (2 * rad + 1)
        for (y in 0 until h) {
            var acc = 0f; val row = y * w
            for (k in -rad..rad) acc += a[row + k.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[row + x] = acc * inv
                acc += a[row + min(w - 1, x + rad + 1)] - a[row + max(0, x - rad)]
            }
        }
        for (x in 0 until w) {
            var acc = 0f
            for (k in -rad..rad) acc += tmp[k.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = acc * inv
                acc += tmp[min(h - 1, y + rad + 1) * w + x] - tmp[max(0, y - rad) * w + x]
            }
        }
        return out
    }

    /** One bilateral pass; range weights from a luminance-difference LUT, pixels with mask < 0.02 are skipped. */
    private fun bilateral(
        r: FloatArray, g: FloatArray, b: FloatArray, mask: FloatArray, w: Int, h: Int, rad: Int, sr: Float,
    ): Array<FloatArray> {
        val n = w * h
        val or_ = r.copyOf(); val og = g.copyOf(); val ob = b.copyOf()
        val side = 2 * rad + 1
        val ws = FloatArray(side * side)
        val sig2 = (rad * 0.7f) * (rad * 0.7f)
        var k = 0
        for (dy in -rad..rad) for (dx in -rad..rad) ws[k++] = exp(-(dx * dx + dy * dy) / (2 * sig2))
        val lut = FloatArray(256) { exp(-(it * it) / (2 * sr * sr)) }
        val l = FloatArray(n) { 0.299f * r[it] + 0.587f * g[it] + 0.114f * b[it] }
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (mask[i] < 0.02f) continue
            var sw = 0f; var s0 = 0f; var s1 = 0f; var s2 = 0f
            var q = 0
            val li = l[i]
            for (dy in -rad..rad) {
                val yy = (y + dy).coerceIn(0, h - 1) * w
                for (dx in -rad..rad) {
                    val j = yy + (x + dx).coerceIn(0, w - 1)
                    val d = abs(l[j] - li).toInt()
                    val wt = ws[q++] * lut[if (d > 255) 255 else d]
                    sw += wt; s0 += wt * r[j]; s1 += wt * g[j]; s2 += wt * b[j]
                }
            }
            or_[i] = s0 / sw; og[i] = s1 / sw; ob[i] = s2 / sw
        }
        return arrayOf(or_, og, ob)
    }

    private fun resize(src: IntArray, w: Int, h: Int, tw: Int, th: Int): IntArray {
        val out = IntArray(tw * th)
        val sx = w.toFloat() / tw; val sy = h.toFloat() / th
        for (y in 0 until th) {
            val fy = ((y + 0.5f) * sy - 0.5f).coerceIn(0f, h - 1f); val y0 = fy.toInt(); val y1 = min(h - 1, y0 + 1); val ty = fy - y0
            for (x in 0 until tw) {
                val fx = ((x + 0.5f) * sx - 0.5f).coerceIn(0f, w - 1f); val x0 = fx.toInt(); val x1 = min(w - 1, x0 + 1); val tx = fx - x0
                var res = 0
                for (sh in intArrayOf(24, 16, 8, 0)) {
                    val a = (src[y0 * w + x0] shr sh) and 255; val bq = (src[y0 * w + x1] shr sh) and 255
                    val c = (src[y1 * w + x0] shr sh) and 255; val d = (src[y1 * w + x1] shr sh) and 255
                    val v = (a * (1 - tx) + bq * tx) * (1 - ty) + (c * (1 - tx) + d * tx) * ty
                    res = res or (v.roundToInt().coerceIn(0, 255) shl sh)
                }
                out[y * tw + x] = res
            }
        }
        return out
    }
}
