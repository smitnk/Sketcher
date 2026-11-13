package com.smitnk.sketcher.image

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.floor
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
        val gray0 = toGray(pixels, width * height)
        val (gray, w, h) = downscale(gray0, width, height, settings.maxSide)
        val blurred = gaussianBlur(gray, w, h, 1.4f)

        val edges = detectEdges(blurred, w, h, settings.detail)
        thin(edges, w, h)
        val chains = traceChains(edges, w, h, settings.minStrokeLength)
        val simplified = chains.map { rdp(it, settings.rdpEpsilon) }
        val ordered = orderStrokes(simplified)
        val outline = ordered.map { Stroke(it, OUTLINE_COLOR, OUTLINE_WIDTH) }

        val shading = if (settings.includeShading) {
            makeShading(blurred, w, h, settings)
        } else {
            emptyList()
        }
        return GeneratedDrawing(w, h, outline, shading)
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
            for (x in 0 until w) {
                var acc = 0f
                for (k in -radius..radius) {
                    val xx = (x + k).coerceIn(0, w - 1)
                    acc += src[y * w + xx] * kernel[k + radius]
                }
                tmp[y * w + x] = acc
            }
        }
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var acc = 0f
                for (k in -radius..radius) {
                    val yy = (y + k).coerceIn(0, h - 1)
                    acc += tmp[yy * w + x] * kernel[k + radius]
                }
                out[y * w + x] = acc
            }
        }
        return out
    }

    // ---------------------------------------------------------------- Canny-ish edge detection

    private fun detectEdges(g: FloatArray, w: Int, h: Int, detail: Float): BooleanArray {
        val mag = FloatArray(w * h)
        val dir = ByteArray(w * h)
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

        // Non-maximum suppression.
        val nms = FloatArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val m = mag[i]
                if (m <= 0f) continue
                val off = when (dir[i].toInt()) {
                    0 -> 1
                    1 -> w + 1
                    2 -> w
                    else -> w - 1
                }
                if (m >= mag[i + off] && m >= mag[i - off]) nms[i] = m
            }
        }

        // Double threshold + hysteresis.
        val high = 260f - 200f * detail.coerceIn(0f, 1f)
        val low = high * 0.4f
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
        return edge
    }

    // ---------------------------------------------------------------- Zhang-Suen thinning

    private fun thin(img: BooleanArray, w: Int, h: Int) {
        val toClear = ArrayList<Int>()
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
                if (toClear.isNotEmpty()) {
                    changed = true
                    for (i in toClear) img[i] = false
                }
            }
        }
    }

    // ---------------------------------------------------------------- chain tracing

    private fun traceChains(img: BooleanArray, w: Int, h: Int, minLength: Int): List<FloatArray> {
        val visited = BooleanArray(w * h)
        val result = ArrayList<FloatArray>()

        fun neighbourCount(i: Int): Int {
            val x = i % w
            val y = i / w
            var c = 0
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = x + dx
                val ny = y + dy
                if (nx in 0 until w && ny in 0 until h && img[ny * w + nx]) c++
            }
            return c
        }

        // Next unvisited neighbour; orthogonal neighbours win over diagonal ones.
        fun pickNext(i: Int): Int {
            val x = i % w
            val y = i / w
            var diagonal = -1
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = x + dx
                val ny = y + dy
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                val j = ny * w + nx
                if (!img[j] || visited[j]) continue
                if (dx == 0 || dy == 0) return j
                if (diagonal < 0) diagonal = j
            }
            return diagonal
        }

        fun walk(start: Int, out: ArrayList<Int>) {
            var cur = start
            while (true) {
                val next = pickNext(cur)
                if (next < 0) break
                visited[next] = true
                out.add(next)
                cur = next
            }
        }

        fun trace(start: Int) {
            visited[start] = true
            val forward = ArrayList<Int>()
            forward.add(start)
            walk(start, forward)
            val backward = ArrayList<Int>()
            walk(start, backward)
            val chain = ArrayList<Int>(backward.size + forward.size)
            for (k in backward.indices.reversed()) chain.add(backward[k])
            chain.addAll(forward)
            if (chain.size < minLength) return
            val pts = FloatArray(chain.size * 2)
            for (k in chain.indices) {
                pts[k * 2] = (chain[k] % w) + 0.5f
                pts[k * 2 + 1] = (chain[k] / w) + 0.5f
            }
            result.add(pts)
        }

        // Open chains first (start at endpoints), then whatever is left (loops, branches).
        for (i in img.indices) {
            if (img[i] && !visited[i] && neighbourCount(i) == 1) trace(i)
        }
        for (i in img.indices) {
            if (img[i] && !visited[i]) trace(i)
        }
        return result
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
        return kotlin.math.abs(dy * (px - ax) - dx * (py - ay)) / len
    }

    // ---------------------------------------------------------------- ordering

    /** Greedy nearest-neighbour ordering; strokes may be reversed so the pen travels less. */
    fun orderStrokes(strokes: List<FloatArray>): List<FloatArray> {
        val n = strokes.size
        if (n <= 1) return strokes
        val used = BooleanArray(n)
        val out = ArrayList<FloatArray>(n)
        var cx = 0f
        var cy = 0f
        repeat(n) {
            var best = -1
            var bestD = Float.MAX_VALUE
            var bestReverse = false
            for (i in 0 until n) {
                if (used[i]) continue
                val s = strokes[i]
                val fx = s[0] - cx
                val fy = s[1] - cy
                val df = fx * fx + fy * fy
                val lx = s[s.size - 2] - cx
                val ly = s[s.size - 1] - cy
                val dl = lx * lx + ly * ly
                if (df < bestD) {
                    bestD = df; best = i; bestReverse = false
                }
                if (dl < bestD) {
                    bestD = dl; best = i; bestReverse = true
                }
            }
            used[best] = true
            val chosen = if (bestReverse) reversePairs(strokes[best]) else strokes[best]
            out.add(chosen)
            cx = chosen[chosen.size - 2]
            cy = chosen[chosen.size - 1]
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

    private fun makeShading(gray: FloatArray, w: Int, h: Int, s: GeneratorSettings): List<Stroke> {
        val levels = s.shadingLevels.coerceIn(1, 4)
        val thresholds = FloatArray(levels) { k ->
            if (levels == 1) 150f else 200f - k * (140f / (levels - 1))
        }
        // band[i] = darkest band index the pixel qualifies for, or -1 (paper).
        val band = ByteArray(w * h)
        for (i in band.indices) {
            var b = -1
            for (j in 0 until levels) {
                if (gray[i] < thresholds[j]) b = j else break
            }
            band[i] = b.toByte()
        }

        val result = ArrayList<Stroke>()
        for (k in 0 until levels) {
            val spacing = max(2f, s.hatchSpacing * (1f + 0.6f * (levels - 1 - k)))
            val shade = (110f - k * (70f / max(1, levels - 1))).roundToInt().coerceIn(0, 255)
            val color = (0xFF shl 24) or (shade shl 16) or (shade shl 8) or shade
            hatch(band, w, h, k, spacing, false, color, result)
            if (k == levels - 1 && levels >= 2) {
                hatch(band, w, h, k, spacing, true, color, result)
            }
        }
        return result
    }

    /**
     * Adds diagonal hatch lines for pixels whose band == [target].
     * Not mirrored: lines run along x+y=c (45 degrees); mirrored: along x-y=c.
     * Every other line is reversed so the pen zig-zags top to bottom.
     */
    private fun hatch(
        band: ByteArray, w: Int, h: Int, target: Int, spacing: Float,
        mirrored: Boolean, color: Int, out: MutableList<Stroke>,
    ) {
        val step = spacing * 1.4142135f
        val cMin = if (mirrored) -(h - 1) else 0
        val cMax = if (mirrored) w - 1 else w + h - 2
        var lineIndex = 0
        var c = cMin.toFloat()
        while (c <= cMax) {
            val ci = c.toInt()
            // x range for this line
            val xStart: Int
            val xEnd: Int
            if (mirrored) {
                xStart = max(0, ci)
                xEnd = min(w - 1, ci + h - 1)
            } else {
                xStart = max(0, ci - (h - 1))
                xEnd = min(w - 1, ci)
            }
            val segments = ArrayList<FloatArray>()
            var runStartX = -1
            var lastInX = -1
            var gap = 0
            var x = xStart
            while (x <= xEnd) {
                val y = if (mirrored) x - ci else ci - x
                if (band[y * w + x].toInt() == target) {
                    if (runStartX < 0) runStartX = x
                    lastInX = x
                    gap = 0
                } else if (runStartX >= 0) {
                    gap++
                    if (gap > 2) {
                        addRun(segments, runStartX, lastInX, ci, mirrored)
                        runStartX = -1
                    }
                }
                x++
            }
            if (runStartX >= 0) addRun(segments, runStartX, lastInX, ci, mirrored)

            if (lineIndex % 2 == 1) segments.reverse()
            for (seg in segments) {
                out.add(Stroke(if (lineIndex % 2 == 1) reversePairs(seg) else seg, color, SHADING_WIDTH))
            }
            lineIndex++
            c += step
        }
    }

    private fun addRun(segments: MutableList<FloatArray>, x0: Int, x1: Int, c: Int, mirrored: Boolean) {
        if (x1 - x0 < 3) return
        val y0 = if (mirrored) x0 - c else c - x0
        val y1 = if (mirrored) x1 - c else c - x1
        segments.add(floatArrayOf(x0 + 0.5f, y0 + 0.5f, x1 + 0.5f, y1 + 0.5f))
    }
}
