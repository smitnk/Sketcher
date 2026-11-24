package com.smitnk.sketcher.image.pipeline

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * AniLines "detail" model input (see tools/anilines_lab/run.py): 2 channels [gray 0..1, inverted normalised Sobel
 * magnitude 0..1], image resized to a long side of ~768, reflect-padded to multiples of 16, output cropped back.
 * Pure JVM so it can be unit-tested.
 */
object AniLinesPrep {
    const val MULTIPLE = 16

    /** Size the image is analysed at: long side [longSide] (capped by [maxPixels]), each side >= 16. */
    fun fitSize(w: Int, h: Int, longSide: Int, maxPixels: Int): Pair<Int, Int> {
        require(w > 0 && h > 0)
        var k = longSide.toDouble() / max(w, h)
        var rw = max(MULTIPLE, (w * k).roundToInt())
        var rh = max(MULTIPLE, (h * k).roundToInt())
        if (rw.toLong() * rh > maxPixels) {
            k *= sqrt(maxPixels.toDouble() / (rw.toLong() * rh))
            rw = max(MULTIPLE, (w * k).toInt()); rh = max(MULTIPLE, (h * k).toInt())
        }
        return rw to rh
    }

    fun padded(v: Int): Int = (v + MULTIPLE - 1) / MULTIPLE * MULTIPLE

    /** Mirror index without repeating the edge (numpy "reflect" / OpenCV BORDER_REFLECT_101). */
    fun reflect(i: Int, n: Int): Int {
        if (n == 1) return 0
        val p = 2 * (n - 1)
        val m = ((i % p) + p) % p
        return if (m < n) m else p - m
    }

    /**
     * Planes [gray, invSobel] of [argb] (w x h) resized to rw x rh, reflect-padded to padded(rw) x padded(rh).
     * Layout: 2 * pw * ph floats (channel-major).
     */
    fun planes(argb: IntArray, w: Int, h: Int, rw: Int, rh: Int): FloatArray {
        val gray = FloatArray(rw * rh)
        val sx = w.toFloat() / rw
        val sy = h.toFloat() / rh
        for (y in 0 until rh) {
            val fy = ((y + 0.5f) * sy - 0.5f).coerceIn(0f, (h - 1).toFloat())
            val y0 = floor(fy).toInt(); val y1 = min(h - 1, y0 + 1); val ay = fy - y0
            for (x in 0 until rw) {
                val fx = ((x + 0.5f) * sx - 0.5f).coerceIn(0f, (w - 1).toFloat())
                val x0 = floor(fx).toInt(); val x1 = min(w - 1, x0 + 1); val ax = fx - x0
                val a = lum(argb[y0 * w + x0]) * (1 - ax) + lum(argb[y0 * w + x1]) * ax
                val b = lum(argb[y1 * w + x0]) * (1 - ax) + lum(argb[y1 * w + x1]) * ax
                gray[y * rw + x] = (a * (1 - ay) + b * ay) / 255f
            }
        }
        val sob = sobelInverted(gray, rw, rh)
        val pw = padded(rw); val ph = padded(rh)
        val out = FloatArray(2 * pw * ph)
        for (y in 0 until ph) {
            val ry = reflect(y, rh)
            for (x in 0 until pw) {
                val s = ry * rw + reflect(x, rw)
                out[y * pw + x] = gray[s]
                out[pw * ph + y * pw + x] = sob[s]
            }
        }
        return out
    }

    /** 1 - |Sobel| / max|Sobel| (1 = flat, dark = strong edge); all 1 for a flat image. */
    fun sobelInverted(g: FloatArray, w: Int, h: Int): FloatArray {
        val mag = FloatArray(w * h)
        var mx = 0f
        for (y in 0 until h) for (x in 0 until w) {
            fun v(dx: Int, dy: Int) = g[reflect(y + dy, h) * w + reflect(x + dx, w)]
            val gx = (v(1, -1) + 2 * v(1, 0) + v(1, 1)) - (v(-1, -1) + 2 * v(-1, 0) + v(-1, 1))
            val gy = (v(-1, 1) + 2 * v(0, 1) + v(1, 1)) - (v(-1, -1) + 2 * v(0, -1) + v(1, -1))
            val m = sqrt(gx * gx + gy * gy)
            mag[y * w + x] = m
            if (m > mx) mx = m
        }
        for (i in mag.indices) mag[i] = if (mx > 0f) 1f - mag[i] / mx else 1f
        return mag
    }

    /** Top-left rw x rh crop of a pw-wide plane. */
    fun crop(src: FloatArray, pw: Int, rw: Int, rh: Int): FloatArray {
        val out = FloatArray(rw * rh)
        for (y in 0 until rh) System.arraycopy(src, y * pw, out, y * rw, rw)
        return out
    }

    private fun lum(c: Int): Float = (((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000f
}
