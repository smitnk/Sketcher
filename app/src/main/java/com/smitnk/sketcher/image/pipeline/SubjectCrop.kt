package com.smitnk.sketcher.image.pipeline

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Finds a flat / softly graded background (studio render, product shot, sticker, letterbox bars) that
 * is connected to the image border and returns the subject bounding box plus a background mask.
 * Photos without a clear background yield the full frame and no mask. Pure Kotlin.
 */
object SubjectCrop {

    /**
     * Subject rectangle in input pixels ([x0,x1) x [y0,y1)) and, when a background was found, [bgMask]
     * (w*h, true = background, already shrunk away from the subject so outlines survive).
     */
    class Result(
        val w: Int, val h: Int,
        val x0: Int, val y0: Int, val x1: Int, val y1: Int,
        val bgMask: BooleanArray?,
        val bgFraction: Float,
    ) {
        val hasBackground: Boolean get() = bgMask != null
        val cropW: Int get() = x1 - x0
        val cropH: Int get() = y1 - y0
        val isFull: Boolean get() = x0 == 0 && y0 == 0 && x1 == w && y1 == h
    }

    private const val SMALL = 256
    private const val STEP_TOL = 7      // max-channel difference between neighbours (soft gradients pass)
    private const val DRIFT_TOL = 48    // max-channel drift from the region's seed colour (vignettes)

    fun full(w: Int, h: Int) = Result(w, h, 0, 0, w, h, null, 0f)

    /**
     * Letterbox bars first (phone-gallery screenshots: dark/light bars with a status-bar clock and round
     * buttons), then the flat-background subject search inside the remaining rectangle.
     */
    fun detect(argb: IntArray, w: Int, h: Int, margin: Float = 0.06f): Result {
        if (w < 16 || h < 16 || argb.size < w * h) return full(w, h)
        val lb = letterbox(argb, w, h) ?: return detectInner(argb, w, h, margin)
        val (lx0, ly0, lx1, ly1) = lb
        val iw = lx1 - lx0; val ih = ly1 - ly0
        val inner = IntArray(iw * ih)
        for (y in 0 until ih) System.arraycopy(argb, (ly0 + y) * w + lx0, inner, y * iw, iw)
        val r = detectInner(inner, iw, ih, margin)
        val mask = BooleanArray(w * h) { val x = it % w; val y = it / w; x < lx0 || x >= lx1 || y < ly0 || y >= ly1 }
        r.bgMask?.let { m -> for (y in 0 until ih) for (x in 0 until iw) if (m[y * iw + x]) mask[(ly0 + y) * w + lx0 + x] = true }
        return Result(w, h, lx0 + r.x0, ly0 + r.y0, lx0 + r.x1, ly0 + r.y1, mask, r.bgFraction)
    }

    /**
     * Inner rectangle [x0,y0,x1,y1] after stripping near-uniform dark/light bars from each side, or null.
     * A dark line counts as bar when >= 60% of its pixels are near the bar colour (median of the outermost line),
     * so status-bar glyphs and round buttons do not break the bar; light (white) bars need >= 99.5% and are only
     * taken continuously from the border, so a white backdrop around a narrow figure is never letterbox.
     */
    internal fun letterbox(argb: IntArray, w: Int, h: Int): IntArray? {
        fun px(x: Int, y: Int) = argb[y * w + x]
        fun med(vals: IntArray): Int { vals.sort(); return vals[vals.size / 2] }
        fun barColor(len: Int, at: (Int) -> Int): Int? {
            val r = IntArray(len) { (at(it) shr 16) and 255 }; val g = IntArray(len) { (at(it) shr 8) and 255 }; val b = IntArray(len) { at(it) and 255 }
            val c = (med(r) shl 16) or (med(g) shl 8) or med(b)
            val v = max(med(r), max(med(g), med(b)))
            return if (v <= 40 || min(med(r), min(med(g), med(b))) >= 240) c else null
        }
        fun near(c: Int, d: Int) = max(abs(((c shr 16) and 255) - ((d shr 16) and 255)), max(abs(((c shr 8) and 255) - ((d shr 8) and 255)), abs((c and 255) - (d and 255)))) <= 28
        // returns number of bar lines scanned from the edge
        fun scan(lines: Int, len: Int, at: (Int, Int) -> Int): Int {
            val c = barColor(len) { at(0, it) } ?: return 0
            // White studio backdrops must never read as bars: light bars need near-total uniformity, dark bars 60%.
            val light = ((c shr 16) and 255) > 200 && ((c shr 8) and 255) > 200 && (c and 255) > 200
            val need = if (light) 0.995f else 0.60f
            var n = 0
            while (n < lines) {
                var ok = 0
                for (i in 0 until len) if (near(c, at(n, i))) ok++
                if (ok < need * len) break
                n++
            }
            return n
        }
        val top = scan(h, w) { l, i -> px(i, l) }
        val bot = scan(h, w) { l, i -> px(i, h - 1 - l) }
        val left = scan(w, h) { l, i -> px(l, i) }
        val right = scan(w, h) { l, i -> px(w - 1 - l, i) }
        val minBar = 0.02f
        val t = if (top >= minBar * h) top else 0
        val b = if (bot >= minBar * h) bot else 0
        val l = if (left >= minBar * w) left else 0
        val r = if (right >= minBar * w) right else 0
        if (t + b + l + r == 0) return null
        val x0 = l; val y0 = t; val x1 = w - r; val y1 = h - b
        if (x1 - x0 < 0.3f * w || y1 - y0 < 0.3f * h) return null
        return intArrayOf(x0, y0, x1, y1)
    }

    private fun detectInner(argb: IntArray, w: Int, h: Int, margin: Float): Result {
        if (w < 16 || h < 16 || argb.size < w * h) return full(w, h)
        // ---- box-downsample to <= SMALL
        val sc = max(1, (max(w, h) + SMALL - 1) / SMALL)
        val sw = max(1, w / sc); val sh = max(1, h / sc)
        val n = sw * sh
        val rr = IntArray(n); val gg = IntArray(n); val bb = IntArray(n)
        val k = sc * sc
        for (y in 0 until sh) for (x in 0 until sw) {
            var sr = 0; var sg = 0; var sb = 0
            for (yy in 0 until sc) for (xx in 0 until sc) {
                val c = argb[(y * sc + yy) * w + x * sc + xx]
                sr += (c shr 16) and 255; sg += (c shr 8) and 255; sb += c and 255
            }
            rr[y * sw + x] = sr / k; gg[y * sw + x] = sg / k; bb[y * sw + x] = sb / k
        }
        fun diff(a: Int, b: Int) = max(abs(rr[a] - rr[b]), max(abs(gg[a] - gg[b]), abs(bb[a] - bb[b])))

        // ---- flood fill regions from the border
        val bg = BooleanArray(n)
        val seen = BooleanArray(n)
        val queue = IntArray(n)
        val borderTotal = 2 * (sw + sh) - 4
        var bgCount = 0; var borderBg = 0
        fun isBorder(i: Int): Boolean { val x = i % sw; val y = i / sw; return x == 0 || y == 0 || x == sw - 1 || y == sh - 1 }
        val borderIdx = ArrayList<Int>()
        for (x in 0 until sw) { borderIdx.add(x); if (sh > 1) borderIdx.add((sh - 1) * sw + x) }
        for (y in 1 until sh - 1) { borderIdx.add(y * sw); if (sw > 1) borderIdx.add(y * sw + sw - 1) }
        for (s in borderIdx) {
            if (seen[s]) continue
            var head = 0; var tail = 0
            queue[tail++] = s; seen[s] = true
            var touch = 0
            while (head < tail) {
                val i = queue[head++]
                if (isBorder(i)) touch++
                val x = i % sw; val y = i / sw
                for (d in 0 until 4) {
                    val nx = x + (if (d == 0) -1 else if (d == 1) 1 else 0)
                    val ny = y + (if (d == 2) -1 else if (d == 3) 1 else 0)
                    if (nx < 0 || ny < 0 || nx >= sw || ny >= sh) continue
                    val j = ny * sw + nx
                    if (seen[j]) continue
                    if (diff(i, j) > STEP_TOL || diff(s, j) > DRIFT_TOL) continue
                    seen[j] = true; queue[tail++] = j
                }
            }
            // a region is background if it is big, or wide along the border (not just a limb touching the edge)
            if (tail >= 0.12f * n || touch >= 0.08f * borderTotal) {
                for (q in 0 until tail) bg[queue[q]] = true
                bgCount += tail; borderBg += touch
            }
        }
        val bgFrac = bgCount.toFloat() / n
        if (bgFrac < 0.15f || borderBg < 0.40f * borderTotal) return full(w, h)

        // ---- subject = not background, opened to drop hairline seams between backgrounds
        val fg = BooleanArray(n) { !bg[it] }
        val r = max(1, (max(sw, sh) / 128.0).roundToInt())
        val opened = dilate(erode(fg, sw, sh, r), sw, sh, r)
        // keep components that are not tiny compared with the biggest one
        val comp = IntArray(n); val sizes = ArrayList<Int>(); val boxes = ArrayList<IntArray>()
        for (s in 0 until n) {
            if (!opened[s] || comp[s] != 0) continue
            val id = sizes.size + 1
            var head = 0; var tail = 0
            queue[tail++] = s; comp[s] = id
            var bx0 = sw; var by0 = sh; var bx1 = -1; var by1 = -1
            while (head < tail) {
                val i = queue[head++]
                val x = i % sw; val y = i / sw
                bx0 = min(bx0, x); bx1 = max(bx1, x); by0 = min(by0, y); by1 = max(by1, y)
                for (d in 0 until 4) {
                    val nx = x + (if (d == 0) -1 else if (d == 1) 1 else 0)
                    val ny = y + (if (d == 2) -1 else if (d == 3) 1 else 0)
                    if (nx < 0 || ny < 0 || nx >= sw || ny >= sh) continue
                    val j = ny * sw + nx
                    if (opened[j] && comp[j] == 0) { comp[j] = id; queue[tail++] = j }
                }
            }
            sizes.add(tail); boxes.add(intArrayOf(bx0, by0, bx1, by1))
        }
        if (sizes.isEmpty()) return full(w, h)
        val biggest = sizes.max()
        var ux0 = sw; var uy0 = sh; var ux1 = -1; var uy1 = -1
        for (c in sizes.indices) if (sizes[c] >= 0.05f * biggest) {
            val b = boxes[c]
            ux0 = min(ux0, b[0]); uy0 = min(uy0, b[1]); ux1 = max(ux1, b[2]); uy1 = max(uy1, b[3])
        }
        // small-scale -> full-res rectangle (+margin of the longer subject side)
        val fx0 = ux0 * sc.toFloat(); val fy0 = uy0 * sc.toFloat()
        val fx1 = (ux1 + 1) * sc.toFloat(); val fy1 = (uy1 + 1) * sc.toFloat()
        val m = margin * max(fx1 - fx0, fy1 - fy0)
        val x0 = floor(max(0f, fx0 - m)).toInt()
        val y0 = floor(max(0f, fy0 - m)).toInt()
        val x1 = ceil(min(w.toFloat(), fx1 + m)).toInt()
        val y1 = ceil(min(h.toFloat(), fy1 + m)).toInt()
        if (x1 - x0 < 8 || y1 - y0 < 8) return full(w, h)

        // ---- background mask: flood-fill bg minus a band around the subject, upsampled
        val shrink = dilate(fg, sw, sh, max(2, (max(sw, sh) * 0.012f).roundToInt()))
        val mask = BooleanArray(w * h)
        for (y in 0 until h) {
            val sy = min(sh - 1, y / sc)
            for (x in 0 until w) {
                val i = min(sw - 1, x / sc) + sy * sw
                mask[y * w + x] = bg[i] && !shrink[i]
            }
        }
        return Result(w, h, x0, y0, x1, y1, mask, bgFrac)
    }

    private fun erode(a: BooleanArray, w: Int, h: Int, r: Int): BooleanArray = morph(a, w, h, r, true)
    private fun dilate(a: BooleanArray, w: Int, h: Int, r: Int): BooleanArray = morph(a, w, h, r, false)

    /** Separable square structuring element via running counts. */
    private fun morph(a: BooleanArray, w: Int, h: Int, r: Int, erode: Boolean): BooleanArray {
        val tmp = BooleanArray(a.size)
        for (y in 0 until h) for (x in 0 until w) {
            var res = erode
            for (dx in -r..r) {
                val xx = x + dx
                val v = if (xx < 0 || xx >= w) erode else a[y * w + xx]   // outside counts as "on" for erode so edge subjects survive
                if (erode && !v) { res = false; break }
                if (!erode && v) { res = true; break }
            }
            tmp[y * w + x] = res
        }
        val out = BooleanArray(a.size)
        for (y in 0 until h) for (x in 0 until w) {
            var res = erode
            for (dy in -r..r) {
                val yy = y + dy
                val v = if (yy < 0 || yy >= h) erode else tmp[yy * w + x]
                if (erode && !v) { res = false; break }
                if (!erode && v) { res = true; break }
            }
            out[y * w + x] = res
        }
        return out
    }
}
