package com.smitnk.sketcher.image.pipeline

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Cartoon detection and artist-ink-line extraction. Pure Kotlin.
 *
 * Ink = thin, very dark strokes (black-hat of the max-channel "value" image), so flat colour
 * boundaries and dark textures (grass squiggles) are not drawn. Colour-region boundaries between
 * large flat regions are only added, at low weight, where no ink line exists.
 */
object CartoonLineExtractor {

    // ------------------------------------------------------------------ public API

    /** 0..1 likelihood that the image is a flat-colour cartoon with ink outlines (>= 0.6 = cartoon). */
    fun cartoonScore(argb: IntArray, w: Int, h: Int): Float {
        if (w < 8 || h < 8) return 0f
        val f = features(argb, w, h)
        val conc = ramp(f[0], 0.45f, 0.85f)     // dominant-colour concentration
        val flat = ramp(f[1], 0.55f, 0.90f)     // flat-pixel fraction
        val ink = bump(f[2], 0.004f, 0.015f, 0.18f, 0.40f) // thin dark pixel fraction
        val sharp = ramp(f[3], 0.35f, 0.75f)    // step-edge share among edge pixels
        // ink lines are required: without them it is a flat illustration, not an inked cartoon
        val s = 0.30f * conc + 0.30f * flat + 0.25f * ink + 0.15f * sharp
        return (s * (0.35f + 0.65f * ink)).coerceIn(0f, 1f)
    }

    /** Clean ink map, 0 = paper, 1 = ink. [detail] 0..1 lowers thresholds and adds region boundaries. */
    fun inkMap(argb: IntArray, w: Int, h: Int, detail: Float): FloatArray {
        val n = w * h
        if (w < 8 || h < 8) return FloatArray(n)
        val d = detail.coerceIn(0f, 1f)
        val v = valueChannel(argb, n)
        val strength = inkStrength(v, w, h, d)
        val long = max(w, h)

        // threshold + connected-component filtering (drops speckle and small texture marks)
        val mask = BooleanArray(n) { strength[it] >= 0.5f }
        val minDiag = long * (0.030f - 0.014f * d)
        val minArea = max(10f, w.toFloat() * h * (0.00007f - 0.00003f * d))
        filterComponents(mask, w, h, minDiag, minArea)
        val fillV = (long * 0.045f).let { it * it }
        fillDarkHoles(mask, v, w, h, fillV, 100)

        val out = FloatArray(n)
        for (i in 0 until n) if (mask[i]) out[i] = max(0.5f, strength[i]).let { if (strength[i] < 0.5f) 1f else it }

        if (d > 0.15f) addRegionBoundaries(argb, w, h, d, mask, out, long)
        return out
    }

    // ------------------------------------------------------------------ ink strength

    private fun valueChannel(argb: IntArray, n: Int): IntArray {
        val v = IntArray(n)
        for (i in 0 until n) {
            val c = argb[i]
            val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
            v[i] = max(r, max(g, b))
        }
        return v
    }

    /** Per-pixel ink probability 0..1 (soft). Shared by score and map. */
    private fun inkStrength(v: IntArray, w: Int, h: Int, d: Float): FloatArray {
        val n = w * h
        val long = max(w, h)
        // structuring-element radius: lines wider than ~3% of the image are not "lines"
        val r = max(2, (long * 0.0075f).roundToInt())
        val closed = slidingMin(slidingMax(v, w, h, r), w, h, r)

        // darkest ink level of the image (1st percentile of value) decides how "black" ink must be
        val hist = IntArray(256)
        for (x in v) hist[x]++
        var acc = 0; var p1 = 0
        val target = (n * 0.01f).toInt()
        for (i in 0 until 256) { acc += hist[i]; if (acc >= target) { p1 = i; break } }
        val hi = (p1 + 55f + 30f * d).coerceIn(70f, 130f)   // fully-not-ink above this
        val lo = hi - 35f                                    // fully ink below this
        val bhLo = 28f - 8f * d
        val bhHi = bhLo + 30f

        val out = FloatArray(n)
        for (i in 0 until n) {
            val vv = v[i].toFloat()
            val dark = ramp(hi - vv, 0f, hi - lo)           // 1 when vv <= lo
            if (dark <= 0f) continue
            val bh = ramp((closed[i] - v[i]).toFloat(), bhLo, bhHi)
            out[i] = dark * bh
        }
        return out
    }

    // ------------------------------------------------------------------ morphology

    private fun slidingMax(src: IntArray, w: Int, h: Int, r: Int): IntArray = slide(slide(src, w, h, r, true, true), w, h, r, true, false)
    private fun slidingMin(src: IntArray, w: Int, h: Int, r: Int): IntArray = slide(slide(src, w, h, r, false, true), w, h, r, false, false)

    /** Separable 1-D running max/min with window 2r+1 along rows or columns (monotonic deque). */
    private fun slide(src: IntArray, w: Int, h: Int, r: Int, isMax: Boolean, horizontal: Boolean): IntArray {
        val out = IntArray(src.size)
        val len = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val step = if (horizontal) 1 else w
        val dq = IntArray(len + 1)
        val sgn = if (isMax) 1 else -1
        for (l in 0 until lines) {
            val base = if (horizontal) l * w else l
            var head = 0; var tail = 0
            var next = 0
            for (i in 0 until len) {
                val hiIdx = min(len - 1, i + r)
                while (next <= hiIdx) {
                    val value = sgn * src[base + next * step]
                    while (tail > head && sgn * src[base + dq[tail - 1] * step] <= value) tail--
                    dq[tail++] = next
                    next++
                }
                while (dq[head] < i - r) head++
                out[base + i * step] = src[base + dq[head] * step]
            }
        }
        return out
    }

    // ------------------------------------------------------------------ components

    /** Removes 8-connected components whose bbox diagonal < minDiag or pixel count < minArea. */
    private fun filterComponents(mask: BooleanArray, w: Int, h: Int, minDiag: Float, minArea: Float) {
        val n = w * h
        val visited = BooleanArray(n)
        val stack = IntArray(n)
        val comp = IntArray(n)
        val minDiag2 = minDiag * minDiag
        for (s in 0 until n) {
            if (!mask[s] || visited[s]) continue
            var sp = 0; var cnt = 0
            stack[sp++] = s; visited[s] = true
            var x0 = w; var x1 = 0; var y0 = h; var y1 = 0
            while (sp > 0) {
                val p = stack[--sp]
                comp[cnt++] = p
                val x = p % w; val y = p / w
                if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
                for (dy in -1..1) {
                    val yy = y + dy
                    if (yy < 0 || yy >= h) continue
                    for (dx in -1..1) {
                        val xx = x + dx
                        if (xx < 0 || xx >= w) continue
                        val q = yy * w + xx
                        if (mask[q] && !visited[q]) { visited[q] = true; stack[sp++] = q }
                    }
                }
            }
            val dx = (x1 - x0 + 1).toFloat(); val dy = (y1 - y0 + 1).toFloat()
            if (cnt < minArea || dx * dx + dy * dy < minDiag2) for (k in 0 until cnt) mask[comp[k]] = false
        }
    }

    /** Fills small enclosed holes (e.g. pupils hollowed by the black-hat) if the hole is dark (value < [maxV]). */
    private fun fillDarkHoles(mask: BooleanArray, v: IntArray, w: Int, h: Int, maxArea: Float, maxV: Int) {
        val n = w * h
        val seen = BooleanArray(n)
        val stack = IntArray(n)
        val comp = IntArray(n)
        for (s in 0 until n) {
            if (mask[s] || seen[s]) continue
            var sp = 0; var cnt = 0; var border = false; var dark = 0
            stack[sp++] = s; seen[s] = true
            while (sp > 0) {
                val p = stack[--sp]; comp[cnt++] = p
                if (v[p] < maxV) dark++
                val x = p % w; val y = p / w
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) border = true
                if (x > 0) { val q = p - 1; if (!mask[q] && !seen[q]) { seen[q] = true; stack[sp++] = q } }
                if (x < w - 1) { val q = p + 1; if (!mask[q] && !seen[q]) { seen[q] = true; stack[sp++] = q } }
                if (y > 0) { val q = p - w; if (!mask[q] && !seen[q]) { seen[q] = true; stack[sp++] = q } }
                if (y < h - 1) { val q = p + w; if (!mask[q] && !seen[q]) { seen[q] = true; stack[sp++] = q } }
            }
            if (!border && cnt <= maxArea && dark >= 0.6f * cnt) for (k in 0 until cnt) mask[comp[k]] = true
        }
    }

    // ------------------------------------------------------------------ colour-region boundaries

    private fun addRegionBoundaries(argb: IntArray, w: Int, h: Int, d: Float, inkMask: BooleanArray, out: FloatArray, long: Int) {
        val n = w * h
        // 1) box-smooth by subsampling to <= 512 long side to kill JPEG noise
        val sc = max(1, long / 512)
        val sw = w / sc; val sh = h / sc
        if (sw < 8 || sh < 8) return
        val sn = sw * sh
        val rr = IntArray(sn); val gg = IntArray(sn); val bb = IntArray(sn)
        for (y in 0 until sh) for (x in 0 until sw) {
            var sr = 0; var sg = 0; var sb = 0
            for (yy in 0 until sc) for (xx in 0 until sc) {
                val c = argb[(y * sc + yy) * w + x * sc + xx]
                sr += (c shr 16) and 255; sg += (c shr 8) and 255; sb += c and 255
            }
            val k = sc * sc
            rr[y * sw + x] = sr / k; gg[y * sw + x] = sg / k; bb[y * sw + x] = sb / k
        }
        // 2) palette of dominant colours (4-bit bins, greedy, merge colours closer than 48)
        val bins = IntArray(4096)
        for (i in 0 until sn) bins[((rr[i] shr 4) shl 8) or ((gg[i] shr 4) shl 4) or (bb[i] shr 4)]++
        val order = (0 until 4096).filter { bins[it] > sn * 0.002f }.sortedByDescending { bins[it] }
        val pal = ArrayList<IntArray>()
        for (b in order) {
            val c = intArrayOf(((b shr 8) and 15) * 16 + 8, ((b shr 4) and 15) * 16 + 8, (b and 15) * 16 + 8)
            if (pal.none { dist2(it, c) < 48 * 48 }) pal.add(c)
            if (pal.size >= 32) break
        }
        if (pal.size < 2) return
        val label = IntArray(sn) { -1 }
        for (i in 0 until sn) {
            var best = -1; var bd = 56 * 56
            for (k in pal.indices) {
                val p = pal[k]
                val dr = rr[i] - p[0]; val dg = gg[i] - p[1]; val db = bb[i] - p[2]
                val dd = dr * dr + dg * dg + db * db
                if (dd < bd) { bd = dd; best = k }
            }
            label[i] = best
        }
        // 3) keep only large connected regions of one label
        val big = BooleanArray(sn)
        val seen = BooleanArray(sn)
        val st = IntArray(sn)
        val cm = IntArray(sn)
        val minRegion = sn * 0.006f
        for (s in 0 until sn) {
            if (seen[s] || label[s] < 0) continue
            var sp = 0; var cnt = 0
            st[sp++] = s; seen[s] = true
            while (sp > 0) {
                val p = st[--sp]; cm[cnt++] = p
                val x = p % sw; val y = p / sw
                if (x > 0) { val q = p - 1; if (!seen[q] && label[q] == label[s]) { seen[q] = true; st[sp++] = q } }
                if (x < sw - 1) { val q = p + 1; if (!seen[q] && label[q] == label[s]) { seen[q] = true; st[sp++] = q } }
                if (y > 0) { val q = p - sw; if (!seen[q] && label[q] == label[s]) { seen[q] = true; st[sp++] = q } }
                if (y < sh - 1) { val q = p + sw; if (!seen[q] && label[q] == label[s]) { seen[q] = true; st[sp++] = q } }
            }
            if (cnt >= minRegion) for (k in 0 until cnt) big[cm[k]] = true
        }
        // 4) boundary where two big regions with strongly different palette colours touch
        val strong = 90 * 90
        val bmask = BooleanArray(n)
        for (y in 0 until sh - 1) for (x in 0 until sw - 1) {
            val i = y * sw + x
            if (!big[i]) continue
            for (q in intArrayOf(i + 1, i + sw)) {
                if (big[q] && label[q] != label[i] && dist2(pal[label[q]], pal[label[i]]) >= strong) {
                    // mark in full-res coordinates (2 px wide band)
                    val qx = q % sw; val qy = q / sw
                    val fx = max(x, qx) * sc; val fy = max(y, qy) * sc
                    for (yy in max(0, fy - 1)..min(h - 1, fy)) for (xx in max(0, fx - 1)..min(w - 1, fx)) bmask[yy * w + xx] = true
                }
            }
        }
        // 5) not where ink already runs (distance to ink)
        val near = max(2, (long * 0.012f).roundToInt())
        val inkRow = BooleanArray(n)
        // cheap dilation of ink by 'near' via separable sliding max on 0/1
        val inkInt = IntArray(n) { if (inkMask[it]) 1 else 0 }
        val dil = slidingMax(inkInt, w, h, near)
        for (i in 0 until n) inkRow[i] = dil[i] == 1
        for (i in 0 until n) if (inkRow[i]) bmask[i] = false
        filterComponents(bmask, w, h, long * 0.04f, 20f)
        val weight = (0.9f * d * d).coerceIn(0f, 0.9f)
        for (i in 0 until n) if (bmask[i] && out[i] < weight) out[i] = weight
    }

    private fun dist2(a: IntArray, b: IntArray): Int {
        val x = a[0] - b[0]; val y = a[1] - b[1]; val z = a[2] - b[2]
        return x * x + y * y + z * z
    }

    // ------------------------------------------------------------------ cartoon features

    /**
     * [0] dominant colour concentration (top-10 bins share), [1] flat-pixel fraction,
     * [2] thin dark ink pixel fraction, [3] step-edge share among edge pixels.
     */
    internal fun features(argb: IntArray, w: Int, h: Int): FloatArray {
        // box-downsample to <= 400 long side (also suppresses JPEG noise)
        val sc = max(1, (max(w, h) + 399) / 400)
        val sw = w / sc; val sh = h / sc
        val sn = sw * sh
        val rr = IntArray(sn); val gg = IntArray(sn); val bb = IntArray(sn)
        val k = sc * sc
        for (y in 0 until sh) for (x in 0 until sw) {
            var sr = 0; var sg = 0; var sb = 0
            for (yy in 0 until sc) for (xx in 0 until sc) {
                val c = argb[(y * sc + yy) * w + x * sc + xx]
                sr += (c shr 16) and 255; sg += (c shr 8) and 255; sb += c and 255
            }
            rr[y * sw + x] = sr / k; gg[y * sw + x] = sg / k; bb[y * sw + x] = sb / k
        }
        // concentration: top-10 of 4096 (4-bit) bins
        val bins = IntArray(4096)
        for (i in 0 until sn) bins[((rr[i] shr 4) shl 8) or ((gg[i] shr 4) shl 4) or (bb[i] shr 4)]++
        val sorted = bins.sortedArrayDescending()
        var top = 0
        for (i in 0 until 10) top += sorted[i]
        val conc = top.toFloat() / sn

        // flatness & edge sharpness from neighbour differences
        var flat = 0; var edge = 0; var step = 0; var cnt = 0
        for (y in 0 until sh - 1) for (x in 0 until sw - 1) {
            val i = y * sw + x
            val g1 = max(abs(rr[i] - rr[i + 1]), max(abs(gg[i] - gg[i + 1]), abs(bb[i] - bb[i + 1])))
            val g2 = max(abs(rr[i] - rr[i + sw]), max(abs(gg[i] - gg[i + sw]), abs(bb[i] - bb[i + sw])))
            val g = max(g1, g2)
            cnt++
            if (g <= 6) flat++
            if (g > 12) { edge++; if (g > 50) step++ }
        }
        val flatF = flat.toFloat() / cnt
        val sharp = if (edge == 0) 0f else step.toFloat() / edge

        // thin dark ink fraction
        val v = IntArray(sn) { max(rr[it], max(gg[it], bb[it])) }
        val st = inkStrength(v, sw, sh, 0.5f)
        var ink = 0
        for (x in st) if (x >= 0.5f) ink++
        return floatArrayOf(conc, flatF, ink.toFloat() / sn, sharp)
    }

    // ------------------------------------------------------------------ helpers

    private fun ramp(x: Float, a: Float, b: Float): Float = ((x - a) / (b - a)).coerceIn(0f, 1f)

    /** trapezoid: 0 below a, 1 on [b,c], 0 above d. */
    private fun bump(x: Float, a: Float, b: Float, c: Float, d: Float): Float =
        min(ramp(x, a, b), 1f - ramp(x, c, d))
}
