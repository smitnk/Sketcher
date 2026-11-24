package com.smitnk.sketcher.image.pipeline

import com.smitnk.sketcher.image.Stroke
import kotlin.math.max
import kotlin.math.min

/** Per-pixel region labels 0..count-1; every pixel (line pixels too) belongs to a region. */
class RegionMap(val width: Int, val height: Int, val labels: IntArray, val count: Int)

/** Ball radii tried in order (largest first); larger radii close wider gaps in the line art. */
enum class GapClosing(val radii: IntArray) {
    SMALL(intArrayOf(3, 2, 1)),
    MEDIUM(intArrayOf(5, 3, 2, 1)),
    LARGE(intArrayOf(8, 5, 3, 2, 1)),
}

/**
 * Trapped-ball region fill for line art: a Kotlin re-implementation of the algorithm in hepesu/LineFiller (MIT).
 * For each radius: erode the free space by a disk, flood the remaining components, grow them back by the radius
 * inside the free space. Then leftovers are flooded, tiny regions dropped, and dropped/line pixels go to the
 * nearest region. Pure JVM (no Android) except [regions] (Bitmap); call it off the main thread.
 */
object LineFill {
    /** Regions smaller than this fraction of the image are merged into a neighbour. */
    private const val MIN_AREA_FRAC = 0.0002f

    fun regions(ink: BooleanArray, width: Int, height: Int, gap: GapClosing = GapClosing.MEDIUM): RegionMap {
        val n = width * height
        require(width > 0 && height > 0 && ink.size >= n) { "ink mask too small" }
        val lab = IntArray(n) { -1 }
        val queue = IntArray(n)
        val avail = BooleanArray(n)
        var next = 0
        for (r in gap.radii) {
            for (i in 0 until n) avail[i] = !ink[i] && lab[i] < 0
            val d2 = sqDistToBlocked(avail, width, height)
            val rr = r * r
            // seeds: components of the eroded free space (pixels where the ball fits)
            var qn = 0
            for (s in 0 until n) {
                if (lab[s] >= 0 || d2[s] <= rr) continue
                val id = next++
                var head = qn
                lab[s] = id; queue[qn++] = s
                while (head < qn) {
                    val c = queue[head++]
                    val x = c % width
                    if (x > 0) { val o = c - 1; if (lab[o] < 0 && d2[o] > rr) { lab[o] = id; queue[qn++] = o } }
                    if (x < width - 1) { val o = c + 1; if (lab[o] < 0 && d2[o] > rr) { lab[o] = id; queue[qn++] = o } }
                    if (c >= width) { val o = c - width; if (lab[o] < 0 && d2[o] > rr) { lab[o] = id; queue[qn++] = o } }
                    if (c < n - width) { val o = c + width; if (lab[o] < 0 && d2[o] > rr) { lab[o] = id; queue[qn++] = o } }
                }
            }
            // grow back r steps inside the free space; all seeds grow together so they cannot leak into each other
            var head = 0
            for (step in 0 until r) {
                val end = qn
                if (head == end) break
                while (head < end) {
                    val c = queue[head++]
                    val id = lab[c]
                    val x = c % width
                    if (x > 0) { val o = c - 1; if (lab[o] < 0 && avail[o]) { lab[o] = id; queue[qn++] = o } }
                    if (x < width - 1) { val o = c + 1; if (lab[o] < 0 && avail[o]) { lab[o] = id; queue[qn++] = o } }
                    if (c >= width) { val o = c - width; if (lab[o] < 0 && avail[o]) { lab[o] = id; queue[qn++] = o } }
                    if (c < n - width) { val o = c + width; if (lab[o] < 0 && avail[o]) { lab[o] = id; queue[qn++] = o } }
                }
            }
        }
        // leftover free pixels: plain flood fill
        for (s in 0 until n) {
            if (ink[s] || lab[s] >= 0) continue
            val id = next++
            var qn = 0; var head = 0
            lab[s] = id; queue[qn++] = s
            while (head < qn) {
                val c = queue[head++]
                val x = c % width
                if (x > 0) { val o = c - 1; if (lab[o] < 0 && !ink[o]) { lab[o] = id; queue[qn++] = o } }
                if (x < width - 1) { val o = c + 1; if (lab[o] < 0 && !ink[o]) { lab[o] = id; queue[qn++] = o } }
                if (c >= width) { val o = c - width; if (lab[o] < 0 && !ink[o]) { lab[o] = id; queue[qn++] = o } }
                if (c < n - width) { val o = c + width; if (lab[o] < 0 && !ink[o]) { lab[o] = id; queue[qn++] = o } }
            }
        }
        // drop tiny regions (keep at least the largest)
        val area = IntArray(next)
        for (i in 0 until n) if (lab[i] >= 0) area[lab[i]]++
        val minArea = max(1, (n * MIN_AREA_FRAC).toInt())
        var largest = -1
        for (k in 0 until next) if (largest < 0 || area[k] > area[largest]) largest = k
        val keep = BooleanArray(next) { area[it] >= minArea || it == largest }
        var qn = 0
        for (i in 0 until n) {
            if (lab[i] >= 0 && !keep[lab[i]]) lab[i] = -1
            if (lab[i] >= 0) queue[qn++] = i
        }
        if (qn == 0) return RegionMap(width, height, IntArray(n), 1) // all ink
        // line pixels and dropped regions join the nearest region (multi-source BFS)
        var head = 0
        while (head < qn) {
            val c = queue[head++]
            val id = lab[c]
            val x = c % width
            if (x > 0 && lab[c - 1] < 0) { lab[c - 1] = id; queue[qn++] = c - 1 }
            if (x < width - 1 && lab[c + 1] < 0) { lab[c + 1] = id; queue[qn++] = c + 1 }
            if (c >= width && lab[c - width] < 0) { lab[c - width] = id; queue[qn++] = c - width }
            if (c < n - width && lab[c + width] < 0) { lab[c + width] = id; queue[qn++] = c + width }
        }
        // compact ids
        val remap = IntArray(next) { -1 }
        var count = 0
        for (i in 0 until n) {
            val k = lab[i]
            if (remap[k] < 0) remap[k] = count++
            lab[i] = remap[k]
        }
        return RegionMap(width, height, lab, count)
    }

    /** Dark (luminance < 128) and mostly opaque pixels are ink. */
    fun regions(lineArt: android.graphics.Bitmap, gap: GapClosing = GapClosing.MEDIUM): RegionMap {
        val w = lineArt.width; val h = lineArt.height
        val px = IntArray(w * h)
        lineArt.getPixels(px, 0, w, 0, 0, w, h)
        return regions(BooleanArray(w * h) { (px[it] ushr 24) >= 128 && lum(px[it]) < 128 }, w, h, gap)
    }

    /** Median ARGB per region (opaque); with [paletteSize] > 0 the region colours are k-means quantised to that many. */
    fun flatColours(map: RegionMap, sourceArgb: IntArray, paletteSize: Int = 0): IntArray {
        val n = map.width * map.height
        require(sourceArgb.size >= n && map.labels.size >= n) { "source too small" }
        val k = map.count
        // counting sort of pixels by region, then per-region channel histograms
        val start = IntArray(k + 1)
        for (i in 0 until n) start[map.labels[i] + 1]++
        for (i in 0 until k) start[i + 1] += start[i]
        val pos = start.copyOf()
        val order = IntArray(n)
        for (i in 0 until n) order[pos[map.labels[i]]++] = i
        val hr = IntArray(256); val hg = IntArray(256); val hb = IntArray(256)
        val out = IntArray(k)
        val area = IntArray(k)
        for (r in 0 until k) {
            hr.fill(0); hg.fill(0); hb.fill(0)
            for (j in start[r] until start[r + 1]) {
                val c = sourceArgb[order[j]]
                hr[(c shr 16) and 255]++; hg[(c shr 8) and 255]++; hb[c and 255]++
            }
            area[r] = start[r + 1] - start[r]
            val half = (area[r] + 1) / 2
            out[r] = (0xFF shl 24) or (median(hr, half) shl 16) or (median(hg, half) shl 8) or median(hb, half)
        }
        return if (paletteSize in 1 until k) quantise(out, area, paletteSize) else out
    }

    /**
     * Flat cel fill as strokes for the existing fill layer: one boustrophedon pass per region, regions largest first
     * (the layer timeline animates strokes in list order). Near-white (paper) regions are skipped.
     */
    internal fun celStrokes(map: RegionMap, colours: IntArray, spacing: Float): List<Stroke> {
        val w = map.width; val h = map.height; val lab = map.labels
        val step = max(1.5f, spacing)
        val width = step * 1.25f
        // runs per region: (y, x0, x1) packed in growable IntArrays
        val runs = arrayOfNulls<IntArray>(map.count)
        val runN = IntArray(map.count)
        var yf = step / 2f
        while (yf < h) {
            val y = yf.toInt()
            var x = 0
            while (x < w) {
                val id = lab[y * w + x]
                val x0 = x
                while (x < w && lab[y * w + x] == id) x++
                var a = runs[id] ?: IntArray(48).also { runs[id] = it }
                if (runN[id] + 3 > a.size) { a = a.copyOf(a.size * 2); runs[id] = a }
                a[runN[id]++] = y; a[runN[id]++] = x0; a[runN[id]++] = x - 1
            }
            yf += step
        }
        val area = IntArray(map.count)
        for (v in lab) area[v]++
        val out = ArrayList<Stroke>()
        for (id in (0 until map.count).sortedByDescending { area[it] }) {
            val a = runs[id] ?: continue
            val c = colours[id]
            if (((c shr 16) and 255) >= 235 && ((c shr 8) and 255) >= 235 && (c and 255) >= 235) continue
            val m = runN[id] / 3
            val used = BooleanArray(m)
            for (s in 0 until m) {
                if (used[s]) continue
                val pts = ArrayList<Float>()
                var cur = s
                var forward = true
                while (true) {
                    used[cur] = true
                    val y = a[cur * 3] + 0.5f
                    val x0 = a[cur * 3 + 1]; val x1 = a[cur * 3 + 2]
                    val ins = min(width * 0.4f, (x1 - x0) / 2f)
                    val l = x0 + 0.5f + ins; val r = x1 + 0.5f - ins
                    if (forward) { pts += l; pts += y; pts += r; pts += y } else { pts += r; pts += y; pts += l; pts += y }
                    // next: first unused overlapping run on the next sampled row (runs are in row order)
                    var nx = -1
                    var q = cur + 1
                    while (q < m && a[q * 3] == a[cur * 3]) q++
                    val ny = if (q < m) a[q * 3] else -1
                    while (q < m && a[q * 3] == ny) {
                        if (!used[q] && a[q * 3 + 1] <= x1 && a[q * 3 + 2] >= x0 && ny - a[cur * 3] <= step + 1) { nx = q; break }
                        q++
                    }
                    if (nx < 0) break
                    cur = nx
                    forward = !forward
                }
                out.add(Stroke(pts.toFloatArray(), c, width))
            }
        }
        return out
    }

    private fun lum(c: Int): Int = (((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000

    private fun median(hist: IntArray, half: Int): Int {
        var acc = 0
        for (v in 0 until 256) { acc += hist[v]; if (acc >= half) return v }
        return 255
    }

    /** Area-weighted k-means in RGB, seeded by the largest regions. */
    private fun quantise(cols: IntArray, area: IntArray, k: Int): IntArray {
        val idx = cols.indices.sortedByDescending { area[it] }
        val cr = FloatArray(k); val cg = FloatArray(k); val cb = FloatArray(k)
        for (j in 0 until k) { val c = cols[idx[j]]; cr[j] = ((c shr 16) and 255).toFloat(); cg[j] = ((c shr 8) and 255).toFloat(); cb[j] = (c and 255).toFloat() }
        val asg = IntArray(cols.size)
        repeat(10) {
            for (i in cols.indices) {
                val c = cols[i]
                val r = ((c shr 16) and 255).toFloat(); val g = ((c shr 8) and 255).toFloat(); val b = (c and 255).toFloat()
                var best = 0; var bd = Float.MAX_VALUE
                for (j in 0 until k) {
                    val d = (r - cr[j]) * (r - cr[j]) + (g - cg[j]) * (g - cg[j]) + (b - cb[j]) * (b - cb[j])
                    if (d < bd) { bd = d; best = j }
                }
                asg[i] = best
            }
            val sr = DoubleArray(k); val sg = DoubleArray(k); val sb = DoubleArray(k); val sw = DoubleArray(k)
            for (i in cols.indices) {
                val c = cols[i]; val j = asg[i]; val wgt = area[i].toDouble()
                sr[j] += ((c shr 16) and 255) * wgt; sg[j] += ((c shr 8) and 255) * wgt; sb[j] += (c and 255) * wgt; sw[j] += wgt
            }
            for (j in 0 until k) if (sw[j] > 0) { cr[j] = (sr[j] / sw[j]).toFloat(); cg[j] = (sg[j] / sw[j]).toFloat(); cb[j] = (sb[j] / sw[j]).toFloat() }
        }
        return IntArray(cols.size) {
            val j = asg[it]
            (0xFF shl 24) or (cr[j].toInt().coerceIn(0, 255) shl 16) or (cg[j].toInt().coerceIn(0, 255) shl 8) or cb[j].toInt().coerceIn(0, 255)
        }
    }

    /** Squared Euclidean distance to the nearest blocked (!free) pixel; image border is open. Felzenszwalb EDT. */
    private fun sqDistToBlocked(free: BooleanArray, w: Int, h: Int): IntArray {
        val inf = 1 shl 28
        val g = IntArray(w * h)
        // columns: 1D distance
        for (x in 0 until w) {
            var d = inf
            for (y in 0 until h) { val i = y * w + x; d = if (!free[i]) 0 else if (d >= inf) inf else d + 1; g[i] = d }
            d = inf
            for (y in h - 1 downTo 0) {
                val i = y * w + x
                d = if (!free[i]) 0 else if (d >= inf) inf else d + 1
                if (d < g[i]) g[i] = d
            }
        }
        for (i in g.indices) g[i] = if (g[i] >= inf) inf else g[i] * g[i]
        // rows: lower envelope of parabolas
        val out = IntArray(w * h)
        val v = IntArray(w); val z = DoubleArray(w + 1); val f = IntArray(w)
        for (y in 0 until h) {
            val o = y * w
            for (x in 0 until w) f[x] = g[o + x]
            var k = 0; v[0] = 0; z[0] = Double.NEGATIVE_INFINITY; z[1] = Double.POSITIVE_INFINITY
            for (q in 1 until w) {
                var s = 0.0
                while (true) {
                    val p = v[k]
                    s = ((f[q].toDouble() + q.toDouble() * q) - (f[p].toDouble() + p.toDouble() * p)) / (2.0 * (q - p))
                    if (s <= z[k] && k > 0) k-- else break
                }
                if (s <= z[k]) { v[0] = q; z[0] = Double.NEGATIVE_INFINITY } else { k++; v[k] = q; z[k] = s }
                z[k + 1] = Double.POSITIVE_INFINITY
            }
            k = 0
            for (x in 0 until w) {
                while (z[k + 1] < x) k++
                val p = v[k]
                out[o + x] = min(inf.toLong(), (x - p).toLong() * (x - p) + f[p]).toInt()
            }
        }
        return out
    }
}
