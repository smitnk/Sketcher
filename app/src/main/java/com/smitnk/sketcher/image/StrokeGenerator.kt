package com.smitnk.sketcher.image

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One pen stroke. [points] holds x,y pairs in image pixel coordinates. */
data class Stroke(val points: FloatArray, val color: Int, val width: Float) {
    /** Total polyline length, computed once. */
    val length: Float by lazy(LazyThreadSafetyMode.NONE) {
        var total = 0f
        var i = 2
        while (i < points.size) {
            val dx = points[i] - points[i - 2]
            val dy = points[i + 1] - points[i - 1]
            total += sqrt(dx * dx + dy * dy)
            i += 2
        }
        total
    }
}

data class GeneratedDrawing(
    val width: Int,
    val height: Int,
    val outline: List<Stroke>,
    val shading: List<Stroke>,
    /** Colour fill strokes (only in colour mode). */
    val fill: List<Stroke> = emptyList(),
)

data class GeneratorSettings(
    /** 0 = few strong edges, 1 = lots of detail. */
    val detail: Float = 0.5f,
    /** Chains with fewer pixels than this are dropped. */
    val minStrokeLength: Int = 12,
    /** Number of luminance bands used for hatching (1..4). */
    val shadingLevels: Int = 3,
    /** Distance between hatch lines (pixels) in the densest band. */
    val hatchSpacing: Float = 6f,
    val includeShading: Boolean = true,
    val maxSide: Int = 1024,
    val rdpEpsilon: Float = 1.2f,
    /** Direction of the main hatch family in degrees (45 = "/"); the cross-hatch is +90. */
    val hatchAngle: Float = 45f,
    /** Max distance (pixels) over which broken outline pieces are bridged; < 0 derives it from [detail]. */
    val gapClose: Float = -1f,
    /** Colour support (see ColorStrokeGenerator). */
    val mode: DrawMode = DrawMode.OUTLINE_SHADING,
    /** Number of colours for fill quantisation (6..16). */
    val fillColors: Int = 10,
    /** Distance between fill scribble lines in pixels. */
    val fillSpacing: Float = 5f,
)

object StrokeGenerator {
    val OUTLINE_COLOR: Int = 0xFF222222.toInt()
    const val OUTLINE_WIDTH = 1.8f
    const val SHADING_WIDTH = 1.2f

    suspend fun generate(
        pixels: IntArray,
        width: Int,
        height: Int,
        settings: GeneratorSettings,
    ): GeneratedDrawing = withContext(Dispatchers.Default) {
        generateBlocking(pixels, width, height, settings)
    }

    /** Pure, synchronous version (used by unit tests). [pixels] are ARGB ints. */
    fun generateBlocking(pixels: IntArray, width: Int, height: Int, settings: GeneratorSettings): GeneratedDrawing {
        require(pixels.size >= width * height) { "pixel array too small" }
        val detail = settings.detail.coerceIn(0f, 1f)
        val gray0 = toGray(pixels, width * height)
        val (gray, w, h) = downscale(gray0, width, height, settings.maxSide)
        val smooth = gaussianBlur(gray, w, h, 1.7f - 0.6f * detail)

        val outline = makeOutline(smooth, w, h, settings, detail)
            .map { Stroke(it, OUTLINE_COLOR, OUTLINE_WIDTH) }
        val shading = if (settings.includeShading) makeShading(gray, w, h, settings, detail) else emptyList()
        return GeneratedDrawing(w, h, outline, shading)
    }

    // ---------------------------------------------------------------- small helpers

    private class IntList(cap: Int = 64) {
        var a = IntArray(cap)
        var n = 0
        fun add(v: Int) {
            if (n == a.size) a = a.copyOf(n * 2)
            a[n++] = v
        }
        fun clear() { n = 0 }
    }

    private class FloatList(cap: Int = 64) {
        var a = FloatArray(cap)
        var n = 0
        fun add(x: Float, y: Float) {
            if (n + 2 > a.size) a = a.copyOf(a.size * 2)
            a[n++] = x
            a[n++] = y
        }
        fun toArray(): FloatArray = a.copyOf(n)
    }

    // ---------------------------------------------------------------- grayscale / scale / blur

    private fun toGray(px: IntArray, n: Int): FloatArray {
        val g = FloatArray(n)
        for (i in 0 until n) {
            val c = px[i]
            val a = ((c ushr 24) and 0xFF) / 255f
            val r = (c shr 16) and 0xFF
            val gg = (c shr 8) and 0xFF
            val b = c and 0xFF
            val lum = 0.299f * r + 0.587f * gg + 0.114f * b
            g[i] = lum * a + 255f * (1f - a) // transparent counts as white
        }
        return g
    }

    private fun downscale(src: FloatArray, w: Int, h: Int, maxSide: Int): Triple<FloatArray, Int, Int> {
        val longest = max(w, h)
        if (longest <= maxSide) return Triple(src, w, h)
        val scale = maxSide.toFloat() / longest
        val nw = max(1, (w * scale).roundToInt())
        val nh = max(1, (h * scale).roundToInt())
        val out = FloatArray(nw * nh)
        for (y in 0 until nh) {
            val fy = ((y + 0.5f) * h / nh - 0.5f).coerceIn(0f, (h - 1).toFloat())
            val y0 = floor(fy).toInt()
            val y1 = min(y0 + 1, h - 1)
            val ty = fy - y0
            for (x in 0 until nw) {
                val fx = ((x + 0.5f) * w / nw - 0.5f).coerceIn(0f, (w - 1).toFloat())
                val x0 = floor(fx).toInt()
                val x1 = min(x0 + 1, w - 1)
                val tx = fx - x0
                val top = src[y0 * w + x0] * (1 - tx) + src[y0 * w + x1] * tx
                val bot = src[y1 * w + x0] * (1 - tx) + src[y1 * w + x1] * tx
                out[y * nw + x] = top * (1 - ty) + bot * ty
            }
        }
        return Triple(out, nw, nh)
    }

    private fun gaussianBlur(src: FloatArray, w: Int, h: Int, sigma: Float): FloatArray {
        val radius = max(1, (sigma * 2.5f).toInt())
        val kernel = FloatArray(radius * 2 + 1)
        var sum = 0f
        for (i in -radius..radius) {
            val v = exp(-(i * i) / (2f * sigma * sigma))
            kernel[i + radius] = v
            sum += v
        }
        for (i in kernel.indices) kernel[i] /= sum

        val tmp = FloatArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var acc = 0f
                if (x >= radius && x < w - radius) {
                    for (k in -radius..radius) acc += src[row + x + k] * kernel[k + radius]
                } else {
                    for (k in -radius..radius) acc += src[row + (x + k).coerceIn(0, w - 1)] * kernel[k + radius]
                }
                tmp[row + x] = acc
            }
        }
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val interior = y >= radius && y < h - radius
            for (x in 0 until w) {
                var acc = 0f
                if (interior) {
                    for (k in -radius..radius) acc += tmp[(y + k) * w + x] * kernel[k + radius]
                } else {
                    for (k in -radius..radius) acc += tmp[(y + k).coerceIn(0, h - 1) * w + x] * kernel[k + radius]
                }
                out[y * w + x] = acc
            }
        }
        return out
    }

    // ---------------------------------------------------------------- outline pipeline

    private fun makeOutline(smooth: FloatArray, w: Int, h: Int, s: GeneratorSettings, detail: Float): List<FloatArray> {
        if (w < 5 || h < 5) return emptyList()
        // Edge strengths -> (dilated) binary map. Dilation merges the two parallel edges of a
        // thick line and closes pinholes; thinning then recovers a single centre line.
        val mergeRadius = if (detail < 0.3f) 3 else 2
        val high = detectEdges(smooth, w, h, detail)
        val strength = dilateMax(high.strength, w, h, mergeRadius)
        val skel = BooleanArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) skel[y * w + x] = strength[y * w + x] > 0f
        thin(skel, w, h)
        pruneSpurs(skel, w, h, mergeRadius * 2 + 4)

        val minLen = max(3, (s.minStrokeLength * (1.6f - 0.8f * detail)).roundToInt())
        val chains = traceChains(skel, strength, w, h, minLen, minLen * 3, high.high * 1.3f)
        val gap = if (s.gapClose >= 0f) s.gapClose else 5f + 12f * detail
        val linked = bridgeGaps(linkEnds(chains, gap), gap * 0.8f)

        val eps = max(0.3f, s.rdpEpsilon * 0.75f)
        val smoothed = ArrayList<FloatArray>(linked.size)
        for (c in linked) {
            val p = smoothChain(c, eps)
            if (p.size >= 4) smoothed.add(p)
        }
        return orderStrokes(smoothed)
    }

    private class EdgeResult(val strength: FloatArray, val high: Float)

    /** Canny-style: Sobel, NMS, hysteresis. Returns the strength of surviving edge pixels (0 elsewhere). */
    private fun detectEdges(g: FloatArray, w: Int, h: Int, detail: Float): EdgeResult {
        val mag = FloatArray(w * h)
        val dir = ByteArray(w * h)
        val gxs = FloatArray(w * h)
        val gys = FloatArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val tl = g[i - w - 1]
                val t = g[i - w]
                val tr = g[i - w + 1]
                val l = g[i - 1]
                val r = g[i + 1]
                val bl = g[i + w - 1]
                val b = g[i + w]
                val br = g[i + w + 1]
                val gx = (tr + 2 * r + br) - (tl + 2 * l + bl)
                val gy = (bl + 2 * b + br) - (tl + 2 * t + tr)
                mag[i] = sqrt(gx * gx + gy * gy)
                gxs[i] = gx
                gys[i] = gy
                var theta = atan2(gy, gx)
                if (theta < 0f) theta += PI.toFloat()
                val deg = theta * 180f / PI.toFloat()
                dir[i] = when {
                    deg < 22.5f || deg >= 157.5f -> 0
                    deg < 67.5f -> 1
                    deg < 112.5f -> 2
                    else -> 3
                }.toByte()
            }
        }

        // Median gradient = texture / noise floor; real edges must stand clear of it.
        val hist = IntArray(2048)
        for (m in mag) hist[min(2047, m.toInt())]++
        var acc = 0
        var median = 0
        val half = mag.size / 2
        while (median < 2047 && acc + hist[median] < half) acc += hist[median++]

        val high = min(320f, max(150f - 110f * detail, 3.2f * median))
        val low = max(high * 0.4f, 1.5f * median)

        val nms = FloatArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val m = mag[i]
                if (m < low) continue
                val off = when (dir[i].toInt()) {
                    0 -> 1
                    1 -> w + 1
                    2 -> w
                    else -> w - 1
                }
                if (m >= mag[i + off] && m >= mag[i - off]) nms[i] = m
            }
        }

        val edge = BooleanArray(w * h)
        val stack = IntArray(w * h)
        var sp = 0
        for (i in nms.indices) {
            if (nms[i] >= high) {
                edge[i] = true
                stack[sp++] = i
            }
        }
        while (sp > 0) {
            val i = stack[--sp]
            val x = i % w
            val y = i / w
            for (dy in -1..1) {
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val j = ny * w + nx
                    if (!edge[j] && nms[j] >= low) {
                        edge[j] = true
                        stack[sp++] = j
                    }
                }
            }
        }
        val strength = FloatArray(w * h)
        for (i in strength.indices) if (edge[i]) strength[i] = nms[i]
        mergeLineEdges(strength, gxs, gys, mag, w, h, 9f + 6f * (1f - detail))
        return EdgeResult(strength, high)
    }

    /**
     * A thick dark line produces two parallel edges with opposite gradients. For every edge pixel look
     * across the darker side for an opposing edge within [maxDist]; replace the pair by one centre pixel.
     * (Only dark ridges are merged, so the light gap between two objects keeps both of its edges.)
     */
    private fun mergeLineEdges(
        strength: FloatArray, gxs: FloatArray, gys: FloatArray, mag: FloatArray,
        w: Int, h: Int, maxDist: Float,
    ) {
        val consumed = IntList(1024)
        val mids = IntList(1024)
        for (y in 2 until h - 2) {
            for (x in 2 until w - 2) {
                val i = y * w + x
                if (strength[i] <= 0f) continue
                val nx = gxs[i] / mag[i]
                val ny = gys[i] / mag[i]
                var t = 2f
                while (t <= maxDist) {
                    val qx = (x - nx * t + 0.5f).toInt()
                    val qy = (y - ny * t + 0.5f).toInt()
                    if (qx < 1 || qy < 1 || qx >= w - 1 || qy >= h - 1) break
                    var found = -1
                    for (dy in -1..1) for (dx in -1..1) {
                        val j = (qy + dy) * w + qx + dx
                        if (strength[j] > 0f && mag[j] > 0f &&
                            (gxs[j] * nx + gys[j] * ny) / mag[j] < -0.75f
                        ) found = j
                    }
                    if (found >= 0) {
                        consumed.add(i)
                        val fx = found % w
                        val fy = found / w
                        mids.add(((y + fy) / 2) * w + (x + fx) / 2)
                        break
                    }
                    t += 1f
                }
            }
        }
        val add = FloatArray(mids.n)
        for (k in 0 until consumed.n) {
            add[k] = strength[consumed.a[k]]
            strength[consumed.a[k]] = 0f
        }
        for (k in 0 until mids.n) if (add[k] > strength[mids.a[k]]) strength[mids.a[k]] = add[k]
    }

    /** Separable square max filter of radius [r]. */
    private fun dilateMax(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        if (r <= 0) return src.copyOf()
        val tmp = FloatArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var m = 0f
                for (k in max(0, x - r)..min(w - 1, x + r)) if (src[row + k] > m) m = src[row + k]
                tmp[row + x] = m
            }
        }
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var m = 0f
                for (k in max(0, y - r)..min(h - 1, y + r)) if (tmp[k * w + x] > m) m = tmp[k * w + x]
                out[y * w + x] = m
            }
        }
        return out
    }

    // ---------------------------------------------------------------- Zhang-Suen thinning

    private fun thin(img: BooleanArray, w: Int, h: Int) {
        val toClear = IntList(4096)
        var changed = true
        while (changed) {
            changed = false
            for (step in 0..1) {
                toClear.clear()
                for (y in 1 until h - 1) {
                    for (x in 1 until w - 1) {
                        val i = y * w + x
                        if (!img[i]) continue
                        val p2 = img[i - w]
                        val p3 = img[i - w + 1]
                        val p4 = img[i + 1]
                        val p5 = img[i + w + 1]
                        val p6 = img[i + w]
                        val p7 = img[i + w - 1]
                        val p8 = img[i - 1]
                        val p9 = img[i - w - 1]
                        var b = 0
                        if (p2) b++
                        if (p3) b++
                        if (p4) b++
                        if (p5) b++
                        if (p6) b++
                        if (p7) b++
                        if (p8) b++
                        if (p9) b++
                        if (b < 2 || b > 6) continue
                        var a = 0
                        if (!p2 && p3) a++
                        if (!p3 && p4) a++
                        if (!p4 && p5) a++
                        if (!p5 && p6) a++
                        if (!p6 && p7) a++
                        if (!p7 && p8) a++
                        if (!p8 && p9) a++
                        if (!p9 && p2) a++
                        if (a != 1) continue
                        val ok = if (step == 0) {
                            !(p2 && p4 && p6) && !(p4 && p6 && p8)
                        } else {
                            !(p2 && p4 && p8) && !(p2 && p6 && p8)
                        }
                        if (ok) toClear.add(i)
                    }
                }
                if (toClear.n > 0) {
                    changed = true
                    for (k in 0 until toClear.n) img[toClear.a[k]] = false
                }
            }
        }
    }

    private fun neighbourCount(img: BooleanArray, i: Int, w: Int): Int {
        var c = 0
        if (img[i - w - 1]) c++
        if (img[i - w]) c++
        if (img[i - w + 1]) c++
        if (img[i - 1]) c++
        if (img[i + 1]) c++
        if (img[i + w - 1]) c++
        if (img[i + w]) c++
        if (img[i + w + 1]) c++
        return c
    }

    /** Number of separate 0->1 transitions around the pixel; >= 3 means a real junction (not a stair step). */
    private fun crossings(img: BooleanArray, i: Int, w: Int): Int {
        val p2 = img[i - w]
        val p3 = img[i - w + 1]
        val p4 = img[i + 1]
        val p5 = img[i + w + 1]
        val p6 = img[i + w]
        val p7 = img[i + w - 1]
        val p8 = img[i - 1]
        val p9 = img[i - w - 1]
        var a = 0
        if (!p2 && p3) a++
        if (!p3 && p4) a++
        if (!p4 && p5) a++
        if (!p5 && p6) a++
        if (!p6 && p7) a++
        if (!p7 && p8) a++
        if (!p8 && p9) a++
        if (!p9 && p2) a++
        return a
    }

    /** Removes short side branches (hairs left by skeletonising) that end in a junction. */
    private fun pruneSpurs(img: BooleanArray, w: Int, h: Int, maxLen: Int) {
        val offs = intArrayOf(-w, 1, w, -1, -w - 1, -w + 1, w - 1, w + 1)
        val path = IntList()
        repeat(2) {
            for (y in 1 until h - 1) {
                for (x in 1 until w - 1) {
                    val start = y * w + x
                    if (!img[start] || neighbourCount(img, start, w) != 1) continue
                    path.clear()
                    path.add(start)
                    var cur = start
                    while (path.n <= maxLen) {
                        var next = -1
                        for (o in offs) {
                            val j = cur + o
                            if (!img[j]) continue
                            var seen = false
                            for (k in 0 until path.n) if (path.a[k] == j) { seen = true; break }
                            if (!seen) { next = j; break }
                        }
                        if (next < 0) break
                        if (crossings(img, next, w) >= 3) {
                            for (k in 0 until path.n) img[path.a[k]] = false
                            break
                        }
                        path.add(next)
                        cur = next
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- chain tracing

    /**
     * Walks the skeleton into pixel chains. At junctions the walk keeps going in the straightest
     * direction so contours stay long; chain ends are snapped onto the line they touch.
     */
    private fun traceChains(
        img: BooleanArray, str: FloatArray, w: Int, h: Int,
        minLen: Int, longLen: Int, strongStrength: Float,
    ): List<FloatArray> {
        val visited = BooleanArray(w * h)
        val owner = IntArray(w * h) { -1 }
        val result = ArrayList<FloatArray>()
        var nextId = 0
        val back = 6

        fun pickNext(cur: Int, out: IntList, start: Int, hx: Float, hy: Float): Int {
            // direction of travel from recent history
            var dx0 = hx
            var dy0 = hy
            if (out.n > 0) {
                val ref = if (out.n > back) out.a[out.n - 1 - back] else start
                dx0 = (cur % w - ref % w).toFloat()
                dy0 = (cur / w - ref / w).toFloat()
            }
            val dl = sqrt(dx0 * dx0 + dy0 * dy0)
            var best = -1
            var bestScore = -10f
            val x = cur % w
            val y = cur / w
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = x + dx
                val ny = y + dy
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                val j = ny * w + nx
                if (!img[j] || visited[j]) continue
                val score = if (dl > 0f) (dx * dx0 + dy * dy0) / (dl * sqrt((dx * dx + dy * dy).toFloat()))
                else if (dx == 0 || dy == 0) 1f else 0.9f
                val adj = if (dx == 0 || dy == 0) score + 0.001f else score
                if (adj > bestScore) { bestScore = adj; best = j }
            }
            return best
        }

        fun walk(start: Int, out: IntList, cid: Int, hx: Float, hy: Float) {
            var cur = start
            while (true) {
                val next = pickNext(cur, out, start, hx, hy)
                if (next < 0) break
                visited[next] = true
                owner[next] = cid
                out.add(next)
                cur = next
            }
        }

        fun foreignNeighbour(p: Int, cid: Int): Int {
            val x = p % w
            val y = p / w
            var best = -1
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = x + dx
                val ny = y + dy
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                val j = ny * w + nx
                if (owner[j] >= 0 && owner[j] != cid) {
                    if (best < 0 || dx == 0 || dy == 0) best = j
                }
            }
            return best
        }

        fun trace(start: Int) {
            val cid = nextId++
            visited[start] = true
            owner[start] = cid
            val forward = IntList()
            forward.add(start)
            walk(start, forward, cid, 0f, 0f)
            // backward walk continues opposite to the forward direction
            val k = min(back, forward.n - 1)
            val hx = if (k > 0) (start % w - forward.a[k] % w).toFloat() else 0f
            val hy = if (k > 0) (start / w - forward.a[k] / w).toFloat() else 0f
            val backward = IntList()
            walk(start, backward, cid, hx, hy)
            val chain = IntList(backward.n + forward.n + 2)
            for (q in backward.n - 1 downTo 0) chain.add(backward.a[q])
            for (q in 0 until forward.n) chain.add(forward.a[q])

            var strSum = 0f
            for (q in 0 until chain.n) strSum += str[chain.a[q]]
            val mean = strSum / chain.n
            val keep = chain.n >= minLen && (chain.n >= longLen || mean >= strongStrength)
            if (!keep) {
                for (q in 0 until chain.n) owner[chain.a[q]] = -1
                return
            }
            // snap both ends onto whatever they touch (closing loops, joining T junctions)
            val first = chain.a[0]
            val last = chain.a[chain.n - 1]
            val fdx = abs(first % w - last % w)
            val fdy = abs(first / w - last / w)
            val closed = chain.n >= 8 && fdx <= 1 && fdy <= 1
            val pts = FloatList(chain.n * 2 + 8)
            val fn = if (closed) -1 else foreignNeighbour(first, cid)
            if (fn >= 0) pts.add(fn % w + 0.5f, fn / w + 0.5f)
            for (q in 0 until chain.n) pts.add(chain.a[q] % w + 0.5f, chain.a[q] / w + 0.5f)
            if (closed) {
                pts.add(first % w + 0.5f, first / w + 0.5f)
            } else {
                val ln = foreignNeighbour(last, cid)
                if (ln >= 0) pts.add(ln % w + 0.5f, ln / w + 0.5f)
            }
            result.add(pts.toArray())
        }

        // 1) open chains from their endpoints
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            if (img[i] && !visited[i] && neighbourCount(img, i, w) == 1) trace(i)
        }
        // 2) side branches that start next to an already traced line
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            if (img[i] && !visited[i] && foreignNeighbour(i, -2) >= 0) trace(i)
        }
        // 3) whatever is left (closed loops)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            if (img[i] && !visited[i]) trace(i)
        }
        return result
    }

    // ---------------------------------------------------------------- gap closing

    /** Joins chain ends that point at each other across a small gap. */
    private fun linkEnds(chains: List<FloatArray>, gap: Float): List<FloatArray> {
        val n = chains.size
        if (n == 0 || gap <= 0.5f) return chains
        val ex = FloatArray(2 * n)
        val ey = FloatArray(2 * n)
        val tx = FloatArray(2 * n)
        val ty = FloatArray(2 * n)
        for (i in 0 until n) {
            val p = chains[i]
            val cnt = p.size / 2
            val k = min(6, cnt - 1)
            for (e in 0..1) {
                val a = if (e == 0) 0 else cnt - 1
                val b = if (e == 0) k else cnt - 1 - k
                var vx = p[a * 2] - p[b * 2]
                var vy = p[a * 2 + 1] - p[b * 2 + 1]
                val l = sqrt(vx * vx + vy * vy)
                if (l > 1e-6f) { vx /= l; vy /= l }
                ex[2 * i + e] = p[a * 2]
                ey[2 * i + e] = p[a * 2 + 1]
                tx[2 * i + e] = vx
                ty[2 * i + e] = vy
            }
        }
        val grid = PointGrid(ex, ey, gap)
        val candA = IntList()
        val candB = IntList()
        val cost = ArrayList<Float>()
        for (a in 0 until 2 * n) {
            grid.forEachNear(ex[a], ey[a]) { b ->
                if (b > a) {
                    val vx = ex[b] - ex[a]
                    val vy = ey[b] - ey[a]
                    val d = sqrt(vx * vx + vy * vy)
                    val sameChain = (a / 2 == b / 2)
                    val okChain = !sameChain || chains[a / 2].size / 2 >= 24
                    if (okChain && d <= gap) {
                        val ca = if (d > 1e-4f) (tx[a] * vx + ty[a] * vy) / d else 1f
                        val cb = if (d > 1e-4f) -(tx[b] * vx + ty[b] * vy) / d else 1f
                        if (d <= 2.5f || (ca >= 0.5f && cb >= 0.5f)) {
                            candA.add(a); candB.add(b)
                            cost.add(d * (2.2f - ca - cb) + 0.01f)
                        }
                    }
                }
            }
        }
        if (candA.n == 0) return chains
        val order = (0 until candA.n).sortedBy { cost[it] }
        val other = IntArray(2 * n) { it xor 1 }
        val link = IntArray(2 * n) { -1 }
        val size = IntArray(2 * n) { chains[it / 2].size / 2 }
        for (c in order) {
            val a = candA.a[c]
            val b = candB.a[c]
            if (link[a] != -1 || link[b] != -1) continue
            if (other[a] == b) {
                if (size[a] >= 24) { link[a] = b; link[b] = a }
                continue
            }
            val oa = other[a]
            val ob = other[b]
            link[a] = b; link[b] = a
            other[oa] = ob; other[ob] = oa
            val s = size[a] + size[b]
            size[oa] = s; size[ob] = s
        }

        val done = BooleanArray(n)
        val out = ArrayList<FloatArray>()
        fun append(fl: FloatList, i: Int, fromStart: Boolean) {
            val p = chains[i]
            val cnt = p.size / 2
            if (fromStart) for (q in 0 until cnt) fl.add(p[q * 2], p[q * 2 + 1])
            else for (q in cnt - 1 downTo 0) fl.add(p[q * 2], p[q * 2 + 1])
        }
        fun build(e0: Int) {
            val fl = FloatList()
            var e = e0
            while (true) {
                val i = e / 2
                done[i] = true
                append(fl, i, e % 2 == 0)
                val nxt = link[e xor 1]
                if (nxt == -1) break
                if (done[nxt / 2]) { fl.add(fl.a[0], fl.a[1]); break } // closed ring
                e = nxt
            }
            out.add(fl.toArray())
        }
        for (e in 0 until 2 * n) if (link[e] == -1 && !done[e / 2]) build(e)
        for (i in 0 until n) if (!done[i]) build(2 * i)
        return out
    }

    /** Extends free chain ends to touch a nearby foreign line they point at (T junction gaps). */
    private fun bridgeGaps(chains: List<FloatArray>, reach: Float): List<FloatArray> {
        if (chains.isEmpty() || reach <= 0.5f) return chains
        var total = 0
        for (c in chains) total += c.size / 2
        val px = FloatArray(total)
        val py = FloatArray(total)
        val pc = IntArray(total)
        var o = 0
        for ((ci, c) in chains.withIndex()) {
            for (q in 0 until c.size / 2) {
                px[o] = c[q * 2]; py[o] = c[q * 2 + 1]; pc[o] = ci; o++
            }
        }
        val grid = PointGrid(px, py, reach)
        val out = ArrayList<FloatArray>(chains.size)
        for ((ci, c) in chains.withIndex()) {
            val cnt = c.size / 2
            val closed = cnt > 8 && abs(c[0] - c[c.size - 2]) < 0.01f && abs(c[1] - c[c.size - 1]) < 0.01f
            if (closed || cnt < 3) { out.add(c); continue }
            val k = min(6, cnt - 1)
            var startAdd = -1
            var endAdd = -1
            for (e in 0..1) {
                val a = if (e == 0) 0 else cnt - 1
                val b = if (e == 0) k else cnt - 1 - k
                var vx = c[a * 2] - c[b * 2]
                var vy = c[a * 2 + 1] - c[b * 2 + 1]
                val l = sqrt(vx * vx + vy * vy)
                if (l < 1e-6f) continue
                vx /= l; vy /= l
                var bestD = reach
                var best = -1
                grid.forEachNear(c[a * 2], c[a * 2 + 1]) { q ->
                    if (pc[q] != ci) {
                        val dx = px[q] - c[a * 2]
                        val dy = py[q] - c[a * 2 + 1]
                        val d = sqrt(dx * dx + dy * dy)
                        if (d in 1.5f..bestD && (vx * dx + vy * dy) / d >= 0.7f) { bestD = d; best = q }
                    }
                }
                if (e == 0) startAdd = best else endAdd = best
            }
            if (startAdd < 0 && endAdd < 0) { out.add(c); continue }
            val fl = FloatList(c.size + 8)
            if (startAdd >= 0) fl.add(px[startAdd], py[startAdd])
            for (q in 0 until cnt) fl.add(c[q * 2], c[q * 2 + 1])
            if (endAdd >= 0) fl.add(px[endAdd], py[endAdd])
            out.add(fl.toArray())
        }
        return out
    }

    /** Uniform grid for fixed-radius neighbour queries. */
    private class PointGrid(val xs: FloatArray, val ys: FloatArray, val cell: Float) {
        private val gw: Int
        private val gh: Int
        private val start: IntArray
        private val items: IntArray

        init {
            var mx = 0f
            var my = 0f
            for (i in xs.indices) { if (xs[i] > mx) mx = xs[i]; if (ys[i] > my) my = ys[i] }
            gw = (mx / cell).toInt() + 2
            gh = (my / cell).toInt() + 2
            start = IntArray(gw * gh + 1)
            for (i in xs.indices) start[cellOf(i) + 1]++
            for (i in 0 until gw * gh) start[i + 1] += start[i]
            val fill = start.copyOf()
            items = IntArray(xs.size)
            for (i in xs.indices) items[fill[cellOf(i)]++] = i
        }

        private fun cellOf(i: Int) = (ys[i] / cell).toInt() * gw + (xs[i] / cell).toInt()

        inline fun forEachNear(x: Float, y: Float, f: (Int) -> Unit) {
            val cx = (x / cell).toInt()
            val cy = (y / cell).toInt()
            for (gy in max(0, cy - 1)..min(rows() - 1, cy + 1)) {
                for (gx in max(0, cx - 1)..min(cols() - 1, cx + 1)) {
                    val c = gy * cols() + gx
                    for (k in startAt(c) until startAt(c + 1)) f(itemAt(k))
                }
            }
        }

        fun rows() = gh
        fun cols() = gw
        fun startAt(c: Int) = start[c]
        fun itemAt(k: Int) = items[k]
    }

    // ---------------------------------------------------------------- smoothing

    /** Moving average -> RDP -> corner-preserving Chaikin. Handles open and closed chains. */
    private fun smoothChain(raw: FloatArray, eps: Float): FloatArray {
        var n = raw.size / 2
        if (n < 3) return raw
        val closed = n >= 8 && abs(raw[0] - raw[2 * n - 2]) < 0.01f && abs(raw[1] - raw[2 * n - 1]) < 0.01f
        if (closed) n -= 1 // drop duplicated closing point
        val r = if (n >= 120) 4 else if (n >= 40) 3 else 2

        // 1) moving average (open chains keep their end points)
        val avg = FloatArray(n * 2)
        for (i in 0 until n) {
            var sx = 0f
            var sy = 0f
            var cnt = 0
            if (closed) {
                for (k in -r..r) {
                    val j = ((i + k) % n + n) % n
                    sx += raw[j * 2]; sy += raw[j * 2 + 1]; cnt++
                }
            } else {
                val rr = min(r, min(i, n - 1 - i))
                for (k in -rr..rr) { sx += raw[(i + k) * 2]; sy += raw[(i + k) * 2 + 1]; cnt++ }
            }
            avg[i * 2] = sx / cnt
            avg[i * 2 + 1] = sy / cnt
        }

        // 2) RDP (closed chains are split at the point furthest from the start)
        var pts: FloatArray
        if (closed) {
            var far = 0
            var fd = -1f
            for (i in 1 until n) {
                val dx = avg[i * 2] - avg[0]
                val dy = avg[i * 2 + 1] - avg[1]
                val d = dx * dx + dy * dy
                if (d > fd) { fd = d; far = i }
            }
            val firstHalf = rdp(avg.copyOfRange(0, (far + 1) * 2), eps)
            val secondPart = FloatArray((n - far + 1) * 2)
            for (i in far until n) { secondPart[(i - far) * 2] = avg[i * 2]; secondPart[(i - far) * 2 + 1] = avg[i * 2 + 1] }
            secondPart[secondPart.size - 2] = avg[0]; secondPart[secondPart.size - 1] = avg[1]
            val secondHalf = rdp(secondPart, eps)
            // join, dropping the shared far point and the duplicated start point
            pts = FloatArray(firstHalf.size + secondHalf.size - 4)
            System.arraycopy(firstHalf, 0, pts, 0, firstHalf.size)
            System.arraycopy(secondHalf, 2, pts, firstHalf.size, secondHalf.size - 4)
        } else {
            pts = rdp(avg, eps)
        }
        var m = pts.size / 2
        if (m < 2) return pts

        // 3) Chaikin, keeping genuine corners (turn > 50 degrees) and open ends fixed
        var fixed = BooleanArray(m)
        val cosLimit = cos(50f * PI.toFloat() / 180f)
        for (i in 0 until m) {
            val hasPrev = closed || i > 0
            val hasNext = closed || i < m - 1
            if (!hasPrev || !hasNext) { fixed[i] = true; continue }
            val a = (i - 1 + m) % m
            val b = (i + 1) % m
            val ux = pts[i * 2] - pts[a * 2]
            val uy = pts[i * 2 + 1] - pts[a * 2 + 1]
            val vx = pts[b * 2] - pts[i * 2]
            val vy = pts[b * 2 + 1] - pts[i * 2 + 1]
            val lu = sqrt(ux * ux + uy * uy)
            val lv = sqrt(vx * vx + vy * vy)
            if (lu > 1e-6f && lv > 1e-6f && (ux * vx + uy * vy) / (lu * lv) < cosLimit) fixed[i] = true
        }
        repeat(2) {
            val out = FloatList(m * 8)
            val nf = ArrayList<Boolean>(m * 4)
            val segs = if (closed) m else m - 1
            for (sIdx in 0 until segs) {
                val a = sIdx
                val b = (sIdx + 1) % m
                val ax = pts[a * 2]; val ay = pts[a * 2 + 1]
                val bx = pts[b * 2]; val by = pts[b * 2 + 1]
                if (fixed[a]) { out.add(ax, ay); nf.add(true) }
                else { out.add(0.75f * ax + 0.25f * bx, 0.75f * ay + 0.25f * by); nf.add(false) }
                if (!fixed[b]) { out.add(0.25f * ax + 0.75f * bx, 0.25f * ay + 0.75f * by); nf.add(false) }
            }
            if (!closed) { out.add(pts[(m - 1) * 2], pts[(m - 1) * 2 + 1]); nf.add(true) }
            pts = out.toArray()
            m = pts.size / 2
            fixed = BooleanArray(m) { nf[it] }
        }
        if (closed) {
            val res = FloatArray(pts.size + 2)
            System.arraycopy(pts, 0, res, 0, pts.size)
            res[pts.size] = pts[0]; res[pts.size + 1] = pts[1]
            return res
        }
        return pts
    }

    // ---------------------------------------------------------------- Ramer-Douglas-Peucker

    /** Simplifies an x,y pair polyline; keeps first and last point. */
    fun rdp(points: FloatArray, epsilon: Float): FloatArray {
        val n = points.size / 2
        if (n < 3) return points
        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true
        val stack = ArrayList<IntArray>()
        stack.add(intArrayOf(0, n - 1))
        while (stack.isNotEmpty()) {
            val range = stack.removeAt(stack.size - 1)
            val a = range[0]
            val b = range[1]
            var maxD = -1f
            var idx = -1
            for (i in a + 1 until b) {
                val d = pointToLineDistance(
                    points[i * 2], points[i * 2 + 1],
                    points[a * 2], points[a * 2 + 1],
                    points[b * 2], points[b * 2 + 1],
                )
                if (d > maxD) {
                    maxD = d
                    idx = i
                }
            }
            if (idx >= 0 && maxD > epsilon) {
                keep[idx] = true
                stack.add(intArrayOf(a, idx))
                stack.add(intArrayOf(idx, b))
            }
        }
        var count = 0
        for (k in keep) if (k) count++
        val out = FloatArray(count * 2)
        var o = 0
        for (i in 0 until n) {
            if (keep[i]) {
                out[o++] = points[i * 2]
                out[o++] = points[i * 2 + 1]
            }
        }
        return out
    }

    private fun pointToLineDistance(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val len = sqrt(dx * dx + dy * dy)
        if (len < 1e-6f) {
            val ex = px - ax
            val ey = py - ay
            return sqrt(ex * ex + ey * ey)
        }
        return abs(dy * (px - ax) - dx * (py - ay)) / len
    }

    // ---------------------------------------------------------------- ordering

    private fun polylineLength(p: FloatArray): Float {
        var t = 0f
        var i = 2
        while (i < p.size) {
            val dx = p[i] - p[i - 2]
            val dy = p[i + 1] - p[i - 1]
            t += sqrt(dx * dx + dy * dy)
            i += 2
        }
        return t
    }

    /**
     * Pen order: the long contour strokes (the longest ones that together make up ~60% of the ink)
     * come first, then the remaining detail strokes; inside each group greedy nearest-neighbour,
     * reversing strokes when their far end is closer.
     */
    fun orderStrokes(strokes: List<FloatArray>): List<FloatArray> {
        val n = strokes.size
        if (n <= 1) return strokes
        val len = FloatArray(n) { polylineLength(strokes[it]) }
        val byLen = (0 until n).sortedByDescending { len[it] }
        var totalLen = 0f
        for (l in len) totalLen += l
        val major = BooleanArray(n)
        var acc = 0f
        for (i in byLen) {
            if (acc >= 0.6f * totalLen && acc > 0f) break
            major[i] = true
            acc += len[i]
        }
        val used = BooleanArray(n)
        val out = ArrayList<FloatArray>(n)
        var cx = 0f
        var cy = 0f
        // start at the end of the longest stroke that is closest to the top-left corner
        val first = byLen[0]
        val s0 = strokes[first]
        val d0 = s0[0] * s0[0] + s0[1] * s0[1]
        val d1 = s0[s0.size - 2] * s0[s0.size - 2] + s0[s0.size - 1] * s0[s0.size - 1]
        val firstStroke = if (d1 < d0) reversePairs(s0) else s0
        used[first] = true
        out.add(firstStroke)
        cx = firstStroke[firstStroke.size - 2]; cy = firstStroke[firstStroke.size - 1]
        for (phase in 0..1) {
            while (true) {
                var best = -1
                var bestD = Float.MAX_VALUE
                var bestReverse = false
                for (i in 0 until n) {
                    if (used[i] || major[i] != (phase == 0)) continue
                    val s = strokes[i]
                    val fx = s[0] - cx
                    val fy = s[1] - cy
                    val df = fx * fx + fy * fy
                    val lx = s[s.size - 2] - cx
                    val ly = s[s.size - 1] - cy
                    val dl = lx * lx + ly * ly
                    if (df < bestD) { bestD = df; best = i; bestReverse = false }
                    if (dl < bestD) { bestD = dl; best = i; bestReverse = true }
                }
                if (best < 0) break
                used[best] = true
                val chosen = if (bestReverse) reversePairs(strokes[best]) else strokes[best]
                out.add(chosen)
                cx = chosen[chosen.size - 2]
                cy = chosen[chosen.size - 1]
            }
        }
        return out
    }

    private fun reversePairs(p: FloatArray): FloatArray {
        val n = p.size / 2
        val r = FloatArray(p.size)
        for (i in 0 until n) {
            r[i * 2] = p[(n - 1 - i) * 2]
            r[i * 2 + 1] = p[(n - 1 - i) * 2 + 1]
        }
        return r
    }

    // ---------------------------------------------------------------- shading (hatching)

    private fun makeShading(gray: FloatArray, w: Int, h: Int, s: GeneratorSettings, detail: Float): List<Stroke> {
        val levels = s.shadingLevels.coerceIn(1, 4)
        val tone = gaussianBlur(gray, w, h, 4f - 2f * detail)

        // Tone thresholds are relative to the paper (bright background) level.
        val hist = IntArray(256)
        for (v in tone) hist[v.toInt().coerceIn(0, 255)]++
        var cum = 0
        var paper = 255
        val target = (tone.size * 0.92f).toInt()
        for (v in 0..255) { cum += hist[v]; if (cum >= target) { paper = v; break } }
        val paperLevel = paper.toFloat().coerceIn(150f, 255f)

        val spacing = max(2f, s.hatchSpacing)
        val minArea = max(40f, (3f * spacing) * (3f * spacing) * (1.4f - detail)).toInt()
        val minRun = max(10f, 2.2f * spacing)
        val openR = max(3, (spacing * 1.1f).roundToInt())

        // layer = (angle offset, phase factor); darker levels add lines, the darkest cross-hatches
        val layers: List<Pair<Float, Float>> = when (levels) {
            1 -> listOf(0f to 0f)
            2 -> listOf(0f to 0f, 90f to 0f)
            3 -> listOf(0f to 0f, 0f to 0.5f, 90f to 0f)
            else -> listOf(0f to 0f, 0f to 0.5f, 90f to 0f, 90f to 0.5f)
        }
        val lineGap = if (levels == 1) spacing * 1.5f else spacing * 2f

        val result = ArrayList<Stroke>()
        for (k in 0 until levels) {
            val ratio = if (levels == 1) 0.68f else 0.82f - k * (0.56f / (levels - 1))
            val threshold = paperLevel * ratio
            var mask = BooleanArray(w * h) { tone[it] < threshold }
            removeSmallRegions(mask, w, h, minArea, true)   // specks
            removeSmallRegions(mask, w, h, minArea, false)  // pin holes
            mask = boxMorph(boxMorph(mask, w, h, openR, true), w, h, openR, false) // drop line-thin dark features
            mask = erode(mask, w, h)
            val shade = (110f - k * (70f / max(1, levels - 1))).roundToInt().coerceIn(0, 255)
            val color = (0xFF shl 24) or (shade shl 16) or (shade shl 8) or shade
            val (angOff, phase) = layers[k]
            hatchLayer(mask, w, h, s.hatchAngle + angOff, lineGap, phase * lineGap, minRun, color, result)
        }
        return result
    }

    /** Flips connected regions of value [target] smaller than [minArea] (4-connected). */
    private fun removeSmallRegions(mask: BooleanArray, w: Int, h: Int, minArea: Int, target: Boolean) {
        val seen = BooleanArray(w * h)
        val stack = IntList(1024)
        val comp = IntList(1024)
        for (s0 in mask.indices) {
            if (seen[s0] || mask[s0] != target) continue
            comp.clear()
            stack.clear()
            stack.add(s0)
            seen[s0] = true
            while (stack.n > 0) {
                val i = stack.a[--stack.n]
                comp.add(i)
                val x = i % w
                val y = i / w
                if (x > 0 && !seen[i - 1] && mask[i - 1] == target) { seen[i - 1] = true; stack.add(i - 1) }
                if (x < w - 1 && !seen[i + 1] && mask[i + 1] == target) { seen[i + 1] = true; stack.add(i + 1) }
                if (y > 0 && !seen[i - w] && mask[i - w] == target) { seen[i - w] = true; stack.add(i - w) }
                if (y < h - 1 && !seen[i + w] && mask[i + w] == target) { seen[i + w] = true; stack.add(i + w) }
            }
            if (comp.n < minArea) for (k in 0 until comp.n) mask[comp.a[k]] = !target
        }
    }

    /** Square-window erosion (all set) or dilation (any set) of radius [r]; the image border does not erode. */
    private fun boxMorph(src: BooleanArray, w: Int, h: Int, r: Int, erodeIt: Boolean): BooleanArray {
        val tmp = BooleanArray(w * h)
        val pre = IntArray(max(w, h) + 1)
        for (y in 0 until h) {
            for (x in 0 until w) pre[x + 1] = pre[x] + if (src[y * w + x]) 1 else 0
            for (x in 0 until w) {
                val lo = max(0, x - r)
                val hi = min(w - 1, x + r)
                val c = pre[hi + 1] - pre[lo]
                tmp[y * w + x] = if (erodeIt) c == hi - lo + 1 else c > 0
            }
        }
        val out = BooleanArray(w * h)
        for (x in 0 until w) {
            for (y in 0 until h) pre[y + 1] = pre[y] + if (tmp[y * w + x]) 1 else 0
            for (y in 0 until h) {
                val lo = max(0, y - r)
                val hi = min(h - 1, y + r)
                val c = pre[hi + 1] - pre[lo]
                out[y * w + x] = if (erodeIt) c == hi - lo + 1 else c > 0
            }
        }
        return out
    }

    private fun erode(mask: BooleanArray, w: Int, h: Int): BooleanArray {
        val out = BooleanArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            out[i] = mask[i] && mask[i - 1] && mask[i + 1] && mask[i - w] && mask[i + w]
        }
        return out
    }

    /**
     * Straight hatch lines (family at [angleDeg], one every [lineGap] px, offset by [phase]) clipped to
     * [mask]. Runs shorter than [minRun] are dropped; every other line is reversed so the pen zig-zags.
     */
    private fun hatchLayer(
        mask: BooleanArray, w: Int, h: Int, angleDeg: Float, lineGap: Float, phase: Float,
        minRun: Float, color: Int, out: MutableList<Stroke>,
    ) {
        val a = angleDeg * PI.toFloat() / 180f
        val ux = cos(a)
        val uy = -sin(a)
        val nx = sin(a)   // normal, so that p = c*n + t*u
        val ny = cos(a)
        val corners = floatArrayOf(0f, 0f, w - 1f, 0f, 0f, h - 1f, w - 1f, h - 1f)
        var cMin = Float.MAX_VALUE
        var cMax = -Float.MAX_VALUE
        for (q in 0 until 4) {
            val c = corners[q * 2] * nx + corners[q * 2 + 1] * ny
            if (c < cMin) cMin = c
            if (c > cMax) cMax = c
        }
        var j = floor((cMin - phase) / lineGap).toInt()
        var lineIndex = 0
        while (true) {
            val c = phase + j * lineGap
            j++
            if (c > cMax) break
            if (c < cMin) continue
            // clip the infinite line to the image box
            var t0 = -1e9f
            var t1 = 1e9f
            var ok = true
            for (axis in 0..1) {
                val base = if (axis == 0) c * nx else c * ny
                val d = if (axis == 0) ux else uy
                val hi = if (axis == 0) w - 1f else h - 1f
                if (abs(d) < 1e-6f) {
                    if (base < 0f || base > hi) ok = false
                } else {
                    val ta = (0f - base) / d
                    val tb = (hi - base) / d
                    t0 = max(t0, min(ta, tb))
                    t1 = min(t1, max(ta, tb))
                }
            }
            if (!ok || t1 <= t0) continue
            val segs = ArrayList<FloatArray>()
            var runStart = Float.NaN
            var runEnd = 0f
            var gapCount = 0
            var t = t0
            while (t <= t1) {
                val x = (c * nx + t * ux + 0.5f).toInt()
                val y = (c * ny + t * uy + 0.5f).toInt()
                val inside = x in 0 until w && y in 0 until h && mask[y * w + x]
                if (inside) {
                    if (runStart.isNaN()) runStart = t
                    runEnd = t
                    gapCount = 0
                } else if (!runStart.isNaN()) {
                    gapCount++
                    if (gapCount > 2) {
                        if (runEnd - runStart >= minRun) segs.add(segment(c, nx, ny, ux, uy, runStart, runEnd))
                        runStart = Float.NaN
                    }
                }
                t += 1f
            }
            if (!runStart.isNaN() && runEnd - runStart >= minRun) segs.add(segment(c, nx, ny, ux, uy, runStart, runEnd))
            val odd = lineIndex % 2 == 1
            if (odd) segs.reverse()
            for (seg in segs) out.add(Stroke(if (odd) reversePairs(seg) else seg, color, SHADING_WIDTH))
            lineIndex++
        }
    }

    private fun segment(c: Float, nx: Float, ny: Float, ux: Float, uy: Float, t0: Float, t1: Float) =
        floatArrayOf(
            c * nx + t0 * ux + 0.5f, c * ny + t0 * uy + 0.5f,
            c * nx + t1 * ux + 0.5f, c * ny + t1 * uy + 0.5f,
        )
}
