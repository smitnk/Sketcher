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

    /**
     * 0..1 likelihood that the image is a flat-colour cartoon with ink outlines (>= 0.6 = cartoon).
     * Measured on the subject only (the flat background connected to the border is excluded, so a 3D
     * render or photo on a flat backdrop does not look like a cartoon) and requires dark ink lines
     * along a meaningful share of the subject's strong edges (renders use shading, not ink).
     */
    fun cartoonScore(argb: IntArray, w: Int, h: Int): Float {
        if (w < 8 || h < 8) return 0f
        val sub = SubjectCrop.detect(argb, w, h, 0.02f)
        val f = features(argb, w, h, sub)
        val conc = ramp(f[0], 0.45f, 0.85f)     // dominant-colour concentration
        val flat = ramp(f[1], 0.55f, 0.90f)     // flat-pixel fraction
        val ink = bump(f[2], 0.004f, 0.015f, 0.18f, 0.40f) // thin dark pixel fraction
        val sharp = ramp(f[3], 0.35f, 0.75f)    // step-edge share among edge pixels
        // ink lines are required: without them it is a flat illustration, not an inked cartoon
        val s = 0.30f * conc + 0.30f * flat + 0.25f * ink + 0.15f * sharp
        var score = s * (0.35f + 0.65f * ink)
        if (sub.bgFraction > 0f) {
            // clear backdrop: outlines must really enclose the shapes (shaded renders have few)
            score *= ramp(f[4], 0.20f, 0.45f)
        }
        // subject-only features run lower than whole-frame ones: stretch so >= 0.6 stays the cartoon cut
        return (score / 0.65f).coerceIn(0f, 1f)
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

        addDarkBlobs(v, w, h, strength, mask, out, d)
        addSmallRegions(argb, w, h, mask, out)
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
            val gap = (closed[i] - v[i]).toFloat()
            var dark = ramp(hi - vv, 0f, hi - lo)           // 1 when vv <= lo
            // outlines that are dark-blue/brown rather than black: accept up to hi+45 when the
            // local surround is much brighter (relative darkness gate)
            if (vv < hi + 45f) dark = max(dark, ramp(gap, 75f, 110f) * ramp(hi + 45f - vv, 0f, 20f))
            if (dark <= 0f) continue
            out[i] = dark * ramp(gap, bhLo, bhHi)
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


    // ------------------------------------------------------------------ small features

    /**
     * Keeps compact dark blobs (pupils, nose, buttons) that the thin-line black-hat drops.
     * Small blobs become solid dots, large ones a ring plus a centre dot.
     */
    private class Blob(val px: IntArray, val x0: Int, val x1: Int, val y0: Int, val y1: Int, val nearInk: Boolean)

    private fun addDarkBlobs(v: IntArray, w: Int, h: Int, strength: FloatArray, mask: BooleanArray, out: FloatArray, d: Float) {
        val n = w * h
        val long = max(w, h)
        val hist = IntArray(256)
        for (x in v) hist[x]++
        var acc = 0; var p1 = 0
        val target = (n * 0.01f).toInt()
        for (i in 0 until 256) { acc += hist[i]; if (acc >= target) { p1 = i; break } }
        val t = (p1 + 55f + 30f * d).coerceIn(70f, 130f).toInt()
        val dark = BooleanArray(n) { v[it] < t }
        val minA = max(8f, n * 0.00002f); val maxA = n * 0.005f
        val bigA = n * 0.0001f
        val near = max(3, (long * 0.012f).roundToInt())
        val inkInt = IntArray(n) { if (mask[it]) 1 else 0 }
        val inkNear = slidingMax(inkInt, w, h, near)
        val seen = BooleanArray(n); val stack = IntArray(n); val comp = IntArray(n)
        val cands = ArrayList<Blob>()
        for (s in 0 until n) {
            if (!dark[s] || seen[s]) continue
            var sp = 0; var cnt = 0
            stack[sp++] = s; seen[s] = true
            var x0 = w; var x1 = 0; var y0 = h; var y1 = 0; var sumV = 0L; var nearCnt = 0; var inkCnt = 0
            while (sp > 0) {
                val p = stack[--sp]; comp[cnt++] = p
                val x = p % w; val y = p / w
                sumV += v[p]; if (inkNear[p] == 1) nearCnt++; if (mask[p]) inkCnt++
                if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
                if (x > 0) { val q = p - 1; if (dark[q] && !seen[q]) { seen[q] = true; stack[sp++] = q } }
                if (x < w - 1) { val q = p + 1; if (dark[q] && !seen[q]) { seen[q] = true; stack[sp++] = q } }
                if (y > 0) { val q = p - w; if (dark[q] && !seen[q]) { seen[q] = true; stack[sp++] = q } }
                if (y < h - 1) { val q = p + w; if (dark[q] && !seen[q]) { seen[q] = true; stack[sp++] = q } }
            }
            if (cnt < minA || cnt > maxA) continue
            val bw = x1 - x0 + 1; val bh = y1 - y0 + 1
            if (cnt < 0.45f * bw * bh || max(bw, bh) > 2.5f * min(bw, bh)) continue
            if (inkCnt > 0.5f * cnt) continue               // already a drawn line
            val pad = max(3, (0.6f * max(bw, bh)).toInt())
            var rs = 0L; var rc = 0
            for (yy in max(0, y0 - pad)..min(h - 1, y1 + pad)) for (xx in max(0, x0 - pad)..min(w - 1, x1 + pad)) {
                val q = yy * w + xx
                if (!dark[q]) { rs += v[q]; rc++ }
            }
            if (rc == 0) continue
            val contrast = rs.toFloat() / rc - sumV.toFloat() / cnt
            val nearInk = nearCnt > 0
            val ok = if (nearInk) contrast >= 45f && cnt >= minA else contrast >= 80f && cnt >= bigA
            if (!ok) continue
            cands.add(Blob(comp.copyOf(cnt), x0, x1, y0, y1, nearInk))
        }
        // enclosure: flood-fill from the border over non-ink pixels; blobs reachable that way are background
        val outside = BooleanArray(n)
        run {
            var sp = 0
            fun push(p: Int) { if (!mask[p] && !outside[p]) { outside[p] = true; stack[sp++] = p } }
            for (x in 0 until w) { push(x); push((h - 1) * w + x) }
            for (y in 0 until h) { push(y * w); push(y * w + w - 1) }
            while (sp > 0) {
                val p = stack[--sp]; val x = p % w; val y = p / w
                if (x > 0) push(p - 1); if (x < w - 1) push(p + 1); if (y > 0) push(p - w); if (y < h - 1) push(p + w)
            }
        }
        val integ = IntArray((w + 1) * (h + 1))
        for (y in 0 until h) { var row = 0; for (x in 0 until w) { if (mask[y * w + x]) row++; integ[(y + 1) * (w + 1) + x + 1] = integ[y * (w + 1) + x + 1] + row } }
        fun inkIn(xa: Int, xb: Int, ya: Int, yb: Int): Int {
            val x0 = xa.coerceIn(0, w - 1); val x1 = xb.coerceIn(0, w - 1); val y0 = ya.coerceIn(0, h - 1); val y1 = yb.coerceIn(0, h - 1)
            return integ[(y1 + 1) * (w + 1) + x1 + 1] - integ[y0 * (w + 1) + x1 + 1] - integ[(y1 + 1) * (w + 1) + x0] + integ[y0 * (w + 1) + x0]
        }
        val win = max(2f, long * 0.03f)
        for ((i, c) in cands.withIndex()) {
            val cx = (c.x0 + c.x1) / 2; val cy = (c.y0 + c.y1) / 2
            val r = kotlin.math.sqrt(c.px.size / 3.14159f)
            val enclosed = !outside[cy * w + cx] && c.px.none { outside[it] }
            val r2 = (2f * r).toInt() + 1
            val touches = inkIn(c.x0 - r2, c.x1 + r2, c.y0 - r2, c.y1 + r2) > 0
            if (!enclosed && !touches) continue
            var similar = 0
            for ((j, o) in cands.withIndex()) {
                if (i == j) continue
                val ox = (o.x0 + o.x1) / 2; val oy = (o.y0 + o.y1) / 2
                if (kotlin.math.abs(ox - cx) > win || kotlin.math.abs(oy - cy) > win) continue
                val ratio = o.px.size.toFloat() / c.px.size
                if (ratio in 0.33f..3f) similar++
            }
            if (similar >= 4) continue
            val cnt = c.px.size; val comp = c.px; val x0 = c.x0; val x1 = c.x1; val y0 = c.y0; val y1 = c.y1
            val rad = r
            if (cnt < n * 0.0001f) {
                out[cy * w + cx] = 1f                 // tiny: one dot, no ring
            } else if (rad <= long * 0.02f) {
                for (k in 0 until cnt) out[comp[k]] = 1f
            } else {
                val fcx = (x0 + x1) / 2f; val fcy = (y0 + y1) / 2f
                for (k in 0 until cnt) {
                    val p = comp[k]; val x = p % w; val y = p / w
                    var edge = false
                    if (x == 0 || y == 0 || x == w - 1 || y == h - 1) edge = true
                    else if (!dark[p - 1] || !dark[p + 1] || !dark[p - w] || !dark[p + w]) edge = true
                    val dx = x - fcx; val dy = y - fcy
                    if (edge || dx * dx + dy * dy <= (0.3f * rad) * (0.3f * rad)) out[p] = 1f
                }
            }
        }
    }

    /**
     * Small, strongly coloured regions (pink inner ears, cheeks) next to ink: emits their boundary
     * where no ink exists yet. Regions are grown on a box-smoothed image so JPEG noise is ignored.
     */
    private fun addSmallRegions(argb: IntArray, w: Int, h: Int, inkMask: BooleanArray, out: FloatArray) {
        val n = w * h
        val long = max(w, h)
        val sc = max(1, long / 512)
        val sw = w / sc; val sh = h / sc
        if (sw < 8 || sh < 8) return
        val sn = sw * sh
        val rr = IntArray(sn); val gg = IntArray(sn); val bb = IntArray(sn)
        val ink = BooleanArray(sn)
        val k = sc * sc
        for (y in 0 until sh) for (x in 0 until sw) {
            var sr = 0; var sg = 0; var sb = 0; var ic = 0
            for (yy in 0 until sc) for (xx in 0 until sc) {
                val p = (y * sc + yy) * w + x * sc + xx
                val c = argb[p]
                sr += (c shr 16) and 255; sg += (c shr 8) and 255; sb += c and 255
                if (inkMask[p]) ic++
            }
            rr[y * sw + x] = sr / k; gg[y * sw + x] = sg / k; bb[y * sw + x] = sb / k
            ink[y * sw + x] = ic * 3 >= k
        }
        fun cd2(a: Int, b: Int): Int {
            val dr = rr[a] - rr[b]; val dg = gg[a] - gg[b]; val db = bb[a] - bb[b]
            return dr * dr + dg * dg + db * db
        }
        val lab = IntArray(sn) { -1 }
        val st = IntArray(sn); val cm = IntArray(sn)
        val minA = max(6f, sn * 0.00015f); val maxA = sn * 0.006f
        val link = 22 * 22
        val boundary = BooleanArray(sn)
        var id = 0
        for (s in 0 until sn) {
            if (lab[s] >= 0 || ink[s]) continue
            var sp = 0; var cnt = 0
            st[sp++] = s; lab[s] = id
            var x0 = sw; var x1 = 0; var y0 = sh; var y1 = 0
            while (sp > 0) {
                val p = st[--sp]; cm[cnt++] = p
                val x = p % sw; val y = p / sw
                if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
                if (cnt > maxA) { /* too big: keep labelling but cheaply */ }
                if (x > 0) { val q = p - 1; if (lab[q] < 0 && !ink[q] && cd2(p, q) < link) { lab[q] = id; st[sp++] = q } }
                if (x < sw - 1) { val q = p + 1; if (lab[q] < 0 && !ink[q] && cd2(p, q) < link) { lab[q] = id; st[sp++] = q } }
                if (y > 0) { val q = p - sw; if (lab[q] < 0 && !ink[q] && cd2(p, q) < link) { lab[q] = id; st[sp++] = q } }
                if (y < sh - 1) { val q = p + sw; if (lab[q] < 0 && !ink[q] && cd2(p, q) < link) { lab[q] = id; st[sp++] = q } }
            }
            id++
            if (cnt < minA || cnt > maxA) continue
            val bw = x1 - x0 + 1; val bh = y1 - y0 + 1
            if (cnt < 0.4f * bw * bh) continue
            // mean colour of the region vs. mean colour of the non-ink ring around it; count ink contact
            var mr = 0L; var mg = 0L; var mb = 0L
            for (j in 0 until cnt) { mr += rr[cm[j]]; mg += gg[cm[j]]; mb += bb[cm[j]] }
            val cr = mr / cnt; val cg = mg / cnt; val cb = mb / cnt
            var er = 0L; var eg = 0L; var eb = 0L; var en = 0; var edgeN = 0; var inkContact = 0
            val pad = 2
            for (j in 0 until cnt) {
                val p = cm[j]; val x = p % sw; val y = p / sw
                var isEdge = false
                for (dy in -pad..pad) for (dx in -pad..pad) {
                    val xx = x + dx; val yy = y + dy
                    if (xx < 0 || yy < 0 || xx >= sw || yy >= sh) continue
                    val q = yy * sw + xx
                    if (lab[q] == lab[s] && lab[q] >= 0) continue
                    if (abs(dx) <= 1 && abs(dy) <= 1) isEdge = true
                    if (ink[q]) { if (abs(dx) <= 1 && abs(dy) <= 1) inkContact++; continue }
                    er += rr[q]; eg += gg[q]; eb += bb[q]; en++
                }
                if (isEdge) edgeN++
            }
            if (en == 0 || edgeN == 0) continue
            val dr = cr - er / en; val dg = cg - eg / en; val db = cb - eb / en
            if (dr * dr + dg * dg + db * db < 80L * 80L) continue
            // must sit inside ink structure: ink touches a good part of the outline
            if (inkContact < edgeN * 0.2f) continue
            for (j in 0 until cnt) {
                val p = cm[j]; val x = p % sw; val y = p / sw
                var e = false
                if (x == 0 || y == 0 || x == sw - 1 || y == sh - 1 || lab[p - 1] != lab[p] || lab[p + 1] != lab[p] || lab[p - sw] != lab[p] || lab[p + sw] != lab[p]) e = true
                if (e) boundary[p] = true
            }
        }
        val near = max(2, (long * 0.006f).roundToInt())
        val inkInt = IntArray(n) { if (inkMask[it]) 1 else 0 }
        val dil = slidingMax(inkInt, w, h, near)
        for (y in 0 until sh) for (x in 0 until sw) {
            if (!boundary[y * sw + x]) continue
            for (yy in y * sc until min(h, y * sc + sc)) for (xx in x * sc until min(w, x * sc + sc)) {
                val p = yy * w + xx
                if (dil[p] == 0 && out[p] < 0.9f) out[p] = 0.9f
            }
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
    internal fun features(argb: IntArray, w: Int, h: Int): FloatArray = features(argb, w, h, SubjectCrop.full(w, h))

    /** As above plus [4] = share of strong subject edges that have thin dark ink next to them. Subject only. */
    internal fun features(argb: IntArray, w: Int, h: Int, sub: SubjectCrop.Result): FloatArray {
        val cw = sub.cropW; val ch = sub.cropH
        val mask = sub.bgMask
        // box-downsample the crop to <= 400 long side (also suppresses JPEG noise)
        val sc = max(1, (max(cw, ch) + 399) / 400)
        val sw = max(1, cw / sc); val sh = max(1, ch / sc)
        val sn = sw * sh
        val rr = IntArray(sn); val gg = IntArray(sn); val bb = IntArray(sn)
        val use = BooleanArray(sn)
        val k = sc * sc
        for (y in 0 until sh) for (x in 0 until sw) {
            var sr = 0; var sg = 0; var sb = 0; var bgc = 0
            for (yy in 0 until sc) for (xx in 0 until sc) {
                val idx = (sub.y0 + y * sc + yy) * w + sub.x0 + x * sc + xx
                val c = argb[idx]
                if (mask != null && mask[idx]) bgc++
                sr += (c shr 16) and 255; sg += (c shr 8) and 255; sb += c and 255
            }
            val i = y * sw + x
            rr[i] = sr / k; gg[i] = sg / k; bb[i] = sb / k
            use[i] = bgc * 2 < k
        }
        var un = 0
        for (u in use) if (u) un++
        if (un < 16) return floatArrayOf(0f, 0f, 0f, 0f, 0f)
        // concentration: top-10 of 4096 (4-bit) bins
        val bins = IntArray(4096)
        for (i in 0 until sn) if (use[i]) bins[((rr[i] shr 4) shl 8) or ((gg[i] shr 4) shl 4) or (bb[i] shr 4)]++
        val sorted = bins.sortedArrayDescending()
        var top = 0
        for (i in 0 until 10) top += sorted[i]
        val conc = top.toFloat() / un

        // thin dark ink map
        val v = IntArray(sn) { max(rr[it], max(gg[it], bb[it])) }
        val st = inkStrength(v, sw, sh, 0.5f)
        var ink = 0
        for (i in 0 until sn) if (use[i] && st[i] >= 0.5f) ink++
        // ink dilated by 2 px
        val nearInk = BooleanArray(sn)
        for (y in 0 until sh) for (x in 0 until sw) if (st[y * sw + x] >= 0.5f) {
            for (dy in -2..2) for (dx in -2..2) {
                val yy = y + dy; val xx = x + dx
                if (yy in 0 until sh && xx in 0 until sw) nearInk[yy * sw + xx] = true
            }
        }

        // flatness & edge sharpness from neighbour differences
        var flat = 0; var edge = 0; var step = 0; var cnt = 0; var covered = 0
        for (y in 0 until sh - 1) for (x in 0 until sw - 1) {
            val i = y * sw + x
            if (!use[i]) continue
            val g1 = max(abs(rr[i] - rr[i + 1]), max(abs(gg[i] - gg[i + 1]), abs(bb[i] - bb[i + 1])))
            val g2 = max(abs(rr[i] - rr[i + sw]), max(abs(gg[i] - gg[i + sw]), abs(bb[i] - bb[i + sw])))
            val g = max(g1, g2)
            cnt++
            if (g <= 6) flat++
            if (g > 12) { edge++; if (g > 50) { step++; if (nearInk[i] || nearInk[i + 1] || nearInk[i + sw]) covered++ } }
        }
        if (cnt == 0) return floatArrayOf(conc, 0f, 0f, 0f, 0f)
        val flatF = flat.toFloat() / cnt
        val sharp = if (edge == 0) 0f else step.toFloat() / edge
        val cov = if (step == 0) 0f else covered.toFloat() / step
        return floatArrayOf(conc, flatF, ink.toFloat() / un, sharp, cov)
    }

    // ------------------------------------------------------------------ helpers

    private fun ramp(x: Float, a: Float, b: Float): Float = ((x - a) / (b - a)).coerceIn(0f, 1f)

    /** trapezoid: 0 below a, 1 on [b,c], 0 above d. */
    private fun bump(x: Float, a: Float, b: Float, c: Float, d: Float): Float =
        min(ramp(x, a, b), 1f - ramp(x, c, d))
}
