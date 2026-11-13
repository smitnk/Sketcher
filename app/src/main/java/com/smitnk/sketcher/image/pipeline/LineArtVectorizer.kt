package com.smitnk.sketcher.image.pipeline

import com.smitnk.sketcher.image.BrushType
import com.smitnk.sketcher.image.Stroke
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Turns an AI line-art ink map (0 = paper, 1 = ink) into clean pen strokes.
 *
 * Pipeline: blur -> hysteresis threshold -> Zhang-Suen thinning -> spur pruning -> gap bridging ->
 * skeleton graph -> collinear pairing at junctions (so lines run through crossings) ->
 * smoothing (moving average + RDP + Chaikin) -> filtering by length*strength -> ordering.
 * Pure Kotlin, ~0.3 s for 1024x1024 on a desktop JVM.
 */
object LineArtVectorizer {

    fun vectorize(ink0: FloatArray, w: Int, h: Int, detail: Float, lineWidth: Float, maxStrokes: Int): List<Stroke> {
        if (w < 8 || h < 8 || ink0.size < w * h || maxStrokes <= 0) return emptyList()
        val d = detail.coerceIn(0f, 1f)
        val sc = (max(w, h) / 768f).coerceIn(1f, 3f)
        val lineScale = (max(w, h) / 1024f).coerceIn(0.6f, 2.2f)
        val ink = normalizeLocal(ink0, w, h)

        val lo = 0.17f - 0.09f * d
        val hi = 0.50f - 0.20f * d
        val mask = dilate(hysteresis(ink, w, h, lo, hi, (6 * sc * sc).toInt().coerceAtLeast(4)), w, h)
        // clear border so neighbour access never goes out of range
        for (x in 0 until w) { mask[x] = false; mask[(h - 1) * w + x] = false }
        for (y in 0 until h) { mask[y * w] = false; mask[y * w + w - 1] = false }
        thin(mask, w, h)

        // prune spurs & tiny fragments (first pass), then find dense-texture zones and prune harder there
        val spurLen = (7f * sc).toInt()
        val minLen = (10f * sc).toInt()
        var g = Graph.build(mask, w, h)
        g.prune(mask, spurLen, minLen, null)
        g = Graph.build(mask, w, h)
        val tex = textureMap(g, mask, w, h, sc)
        g.prune(mask, spurLen, minLen, tex)
        bridge(mask, ink, w, h, (6f + 7f * d) * sc, lo)
        g = Graph.build(mask, w, h)
        val chains = g.strokes()

        // ---- chains -> smooth strokes (split at sharp corners so weight can vary along long lines)
        val cands = ArrayList<Cand>(chains.size)
        val minScore = (11f - 8f * d) * sc
        for (c in chains) {
            val n = c.size
            if (n < 4) continue
            var texCount = 0
            for (i in 0 until n) if (tex.at(c[i])) texCount++
            val isTex = texCount * 10 >= n * 6
            var pts = FloatArray(n * 2)
            for (i in 0 until n) { pts[i * 2] = (c[i] % w) + 0.5f; pts[i * 2 + 1] = (c[i] / w) + 0.5f }
            pts = movingAverage(movingAverage(pts, 3), 2)
            val cuts = if (!isTex && n > 60 * sc) cornerCuts(pts, sc) else IntArray(0)
            var from = 0
            val bounds = IntArray(cuts.size + 1) { if (it < cuts.size) cuts[it] else n - 1 }
            for (to in bounds) {
                if (to - from < 3) { from = to; continue }
                var sum = 0f
                for (i in from..to) sum += localMax(ink, w, h, c[i])
                val mean = sum / (to - from + 1)
                var p = pts.copyOfRange(from * 2, (to + 1) * 2)
                p = chaikin(chaikin(rdp(p, 0.65f)))
                from = to
                val len = polyLen(p)
                if (len < 5f * sc) continue
                val score = len * mean
                if (score < minScore || mean < 0.17f - 0.06f * d) continue
                cands.add(Cand(p, len, mean, score, isTex, if (isTex) len * Math.exp(-15.0 * turnRate(p, len)).toFloat() else 0f))
            }
        }
        // sparse selection inside texture zones: only the longest, smoothest strokes survive
        val texC = cands.filter { it.tex }.sortedByDescending { it.tscore }
        val keepTex = HashSet<Cand>()
        val budget = (texC.size * 0.2f).toInt().coerceAtLeast(min(texC.size, 1))
        for (c in texC) { if (keepTex.size >= budget) break; if (c.len >= 22f * sc) keepTex.add(c) }
        var kept: List<Cand> = cands.filter { !it.tex || it in keepTex }
        if (kept.size > maxStrokes) kept = kept.sortedByDescending { it.score }.take(maxStrokes)

        val ordered = order(kept)
        val out = ArrayList<Stroke>(ordered.size)
        for (c in ordered) {
            val s = ((c.mean - 0.12f) / 0.5f).coerceIn(0f, 1f)
            val wd = lineWidth * lineScale * (0.5f + 0.8f * s) * (if (c.tex && c.len < 45f * sc) 0.8f else 1f)
            out.add(Stroke(c.pts, 0xFF000000.toInt(), wd, BrushType.PEN, if (c.tex && c.len < 45f * sc) 0.6f else 1f))
        }
        return out
    }

    private class Cand(var pts: FloatArray, val len: Float, val mean: Float, val score: Float, val tex: Boolean = false, val tscore: Float = 0f)

    // ------------------------------------------------------------------ local normalisation

    /** Boosts faint-but-local lines: scales ink by 0.9 / (blend of global and ~48px-local peak), 1..2.2x. */
    private fun normalizeLocal(ink: FloatArray, w: Int, h: Int): FloatArray {
        val hist = IntArray(256)
        for (i in 0 until w * h) hist[(ink[i].coerceIn(0f, 1f) * 255f).toInt()]++
        var acc = 0
        var gb = 255
        val tail = (w * h * 0.003f).toInt()
        while (gb > 0) { acc += hist[gb]; if (acc > tail) break; gb-- }
        val gmax = (gb / 255f).coerceIn(0.3f, 1f)
        val bs = 16
        val bw = (w + bs - 1) / bs; val bh = (h + bs - 1) / bs
        val bm = FloatArray(bw * bh)
        for (y in 0 until h) for (x in 0 until w) { val k = (y / bs) * bw + x / bs; if (ink[y * w + x] > bm[k]) bm[k] = ink[y * w + x] }
        val lm = FloatArray(bw * bh)
        for (by in 0 until bh) for (bx in 0 until bw) {
            var m = 0f
            for (yy in max(0, by - 1)..min(bh - 1, by + 1)) for (xx in max(0, bx - 1)..min(bw - 1, bx + 1)) m = max(m, bm[yy * bw + xx])
            lm[by * bw + bx] = m
        }
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val fy = ((y + 0.5f) / bs - 0.5f).coerceIn(0f, bh - 1f)
            val y0 = fy.toInt(); val y1 = min(bh - 1, y0 + 1); val ty = fy - y0
            for (x in 0 until w) {
                val fx = ((x + 0.5f) / bs - 0.5f).coerceIn(0f, bw - 1f)
                val x0 = fx.toInt(); val x1 = min(bw - 1, x0 + 1); val tx = fx - x0
                val l = (lm[y0 * bw + x0] * (1 - tx) + lm[y0 * bw + x1] * tx) * (1 - ty) +
                    (lm[y1 * bw + x0] * (1 - tx) + lm[y1 * bw + x1] * tx) * ty
                val denom = max(0.4f, 0.5f * gmax + 0.5f * l)
                val boost = (0.9f / denom).coerceIn(1f, 2.2f)
                out[y * w + x] = min(1f, ink[y * w + x] * boost)
            }
        }
        return out
    }

    // ------------------------------------------------------------------ texture zones (fur / grass)

    private class TexMap(val cell: Int, val gw: Int, val gh: Int, val w: Int, val flag: BooleanArray) {
        fun at(p: Int): Boolean = flag[((p / w) / cell).coerceAtMost(gh - 1) * gw + ((p % w) / cell).coerceAtMost(gw - 1)]
    }

    /** Flags 32px cells whose 3x3 neighbourhood has dense skeleton, many short segments and low orientation coherence. */
    private fun textureMap(g: Graph, img: BooleanArray, w: Int, h: Int, sc: Float): TexMap {
        val cell = 32
        val gw = (w + cell - 1) / cell; val gh = (h + cell - 1) / cell
        val px = IntArray(gw * gh); val shortN = IntArray(gw * gh)
        val cx = FloatArray(gw * gh); val sy = FloatArray(gw * gh); val tl = FloatArray(gw * gh)
        for (y in 1 until h - 1) for (x in 1 until w - 1) if (img[y * w + x]) px[(y / cell) * gw + x / cell]++
        val shortLen = 30f * sc
        for (e in 0 until g.nEdges) {
            val o = g.off.a[e]; val l = g.len.a[e]
            if (l < 2) continue
            val mid = g.pool.a[o + l / 2]
            val k = (mid / w / cell).coerceAtMost(gh - 1) * gw + (mid % w / cell).coerceAtMost(gw - 1)
            if (l < shortLen) shortN[k]++
            val a = g.pool.a[o]; val b = g.pool.a[o + l - 1]
            val dx = (b % w - a % w).toFloat(); val dy = (b / w - a / w).toFloat()
            val n2 = dx * dx + dy * dy
            if (n2 < 1f) continue
            // doubled-angle vector weighted by length: coherent lines add up, random ticks cancel
            cx[k] += l * (dx * dx - dy * dy) / n2; sy[k] += l * 2f * dx * dy / n2; tl[k] += l
        }
        val flag = BooleanArray(gw * gh)
        for (gy in 0 until gh) for (gx in 0 until gw) {
            var p = 0; var sn = 0; var ax = 0f; var ay = 0f; var at = 0f; var area = 0
            for (yy in max(0, gy - 1)..min(gh - 1, gy + 1)) for (xx in max(0, gx - 1)..min(gw - 1, gx + 1)) {
                val k = yy * gw + xx
                p += px[k]; sn += shortN[k]; ax += cx[k]; ay += sy[k]; at += tl[k]; area += cell * cell
            }
            val density = p.toFloat() / area
            val coh = if (at > 1f) sqrt(ax * ax + ay * ay) / at else 1f
            flag[gy * gw + gx] = density > 0.085f && sn >= 14 && coh < 0.5f
        }
        return TexMap(cell, gw, gh, w, flag)
    }

    private fun turnRate(p: FloatArray, len: Float): Float = turnTotal(p) / max(len, 1f)

    private fun turnTotal(p: FloatArray): Float {
        var t = 0f
        var i = 4
        while (i < p.size) {
            val ux = p[i - 2] - p[i - 4]; val uy = p[i - 1] - p[i - 3]
            val vx = p[i] - p[i - 2]; val vy = p[i + 1] - p[i - 1]
            t += abs(kotlin.math.atan2(ux * vy - uy * vx, ux * vx + uy * vy))
            i += 2
        }
        return t
    }

    /** Indices (ascending, excluding the final one) where a long chain turns sharply; pieces keep >= 14*sc points. */
    private fun cornerCuts(p: FloatArray, sc: Float): IntArray {
        val n = p.size / 2
        val k = (7 * sc).toInt()
        val minPiece = (14 * sc).toInt()
        val cuts = ArrayList<Int>()
        var last = 0
        var i = k
        while (i < n - k) {
            val ux = p[i * 2] - p[(i - k) * 2]; val uy = p[i * 2 + 1] - p[(i - k) * 2 + 1]
            val vx = p[(i + k) * 2] - p[i * 2]; val vy = p[(i + k) * 2 + 1] - p[i * 2 + 1]
            val ang = abs(kotlin.math.atan2(ux * vy - uy * vx, ux * vx + uy * vy))
            if (ang > 1.1f && i - last >= minPiece && n - 1 - i >= minPiece) {
                // refine to the sharpest point within +-k/2
                var best = i; var ba = ang
                for (j in i + 1..min(n - k - 1, i + k / 2)) {
                    val a2x = p[j * 2] - p[(j - k) * 2]; val a2y = p[j * 2 + 1] - p[(j - k) * 2 + 1]
                    val b2x = p[(j + k) * 2] - p[j * 2]; val b2y = p[(j + k) * 2 + 1] - p[j * 2 + 1]
                    val a2 = abs(kotlin.math.atan2(a2x * b2y - a2y * b2x, a2x * b2x + a2y * b2y))
                    if (a2 > ba) { ba = a2; best = j }
                }
                cuts.add(best); last = best; i = best + k
            } else i++
        }
        return cuts.toIntArray()
    }


    // ------------------------------------------------------------------ preprocessing

    private fun blur(src: FloatArray, w: Int, h: Int): FloatArray {
        val tmp = FloatArray(w * h)
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val r = y * w
            for (x in 0 until w) {
                val a = src[r + max(x - 1, 0)]
                val b = src[r + x]
                val c = src[r + min(x + 1, w - 1)]
                tmp[r + x] = (a + 2 * b + c) * 0.25f
            }
        }
        for (y in 0 until h) {
            val r0 = max(y - 1, 0) * w
            val r1 = y * w
            val r2 = min(y + 1, h - 1) * w
            for (x in 0 until w) out[r1 + x] = (tmp[r0 + x] + 2 * tmp[r1 + x] + tmp[r2 + x]) * 0.25f
        }
        return out
    }

    private fun dilate(m: BooleanArray, w: Int, h: Int): BooleanArray {
        val o = BooleanArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            if (m[i] || m[i - 1] || m[i + 1] || m[i - w] || m[i + w] || m[i - w - 1] || m[i - w + 1] || m[i + w - 1] || m[i + w + 1]) o[i] = true
        }
        return o
    }

    private fun hysteresis(v: FloatArray, w: Int, h: Int, lo: Float, hi: Float, minArea: Int): BooleanArray {
        val mask = BooleanArray(w * h)
        val seen = BooleanArray(w * h)
        val q = IntArray(w * h)
        for (s in 0 until w * h) {
            if (seen[s] || v[s] < lo) continue
            var head = 0
            var tail = 0
            q[tail++] = s
            seen[s] = true
            var peak = 0f
            while (head < tail) {
                val p = q[head++]
                if (v[p] > peak) peak = v[p]
                val x = p % w
                val y = p / w
                for (dy in -1..1) {
                    val yy = y + dy
                    if (yy < 0 || yy >= h) continue
                    for (dx in -1..1) {
                        val xx = x + dx
                        if (xx < 0 || xx >= w) continue
                        val n = yy * w + xx
                        if (!seen[n] && v[n] >= lo) { seen[n] = true; q[tail++] = n }
                    }
                }
            }
            if (peak >= hi && tail >= minArea) for (k in 0 until tail) mask[q[k]] = true
        }
        return mask
    }

    // ------------------------------------------------------------------ Zhang-Suen thinning

    private fun thin(img: BooleanArray, w: Int, h: Int) {
        var buf = IntArray(8192)
        // active pixel list: only set pixels are visited, shrinking every pass
        var pix = IntArray(8192)
        var np = 0
        for (y in 1 until h - 1) {
            var i = y * w + 1
            for (x in 1 until w - 1) {
                if (img[i]) { if (np == pix.size) pix = pix.copyOf(np * 2); pix[np++] = i }
                i++
            }
        }
        var changed = true
        while (changed) {
            changed = false
            for (step in 0..1) {
                var n = 0
                for (k in 0 until np) {
                    val i = pix[k]
                    val p2 = img[i - w]; val p3 = img[i - w + 1]; val p4 = img[i + 1]; val p5 = img[i + w + 1]
                    val p6 = img[i + w]; val p7 = img[i + w - 1]; val p8 = img[i - 1]; val p9 = img[i - w - 1]
                    var b = 0
                    if (p2) b++; if (p3) b++; if (p4) b++; if (p5) b++
                    if (p6) b++; if (p7) b++; if (p8) b++; if (p9) b++
                    if (b < 2 || b > 6) continue
                    var a = 0
                    if (!p2 && p3) a++; if (!p3 && p4) a++; if (!p4 && p5) a++; if (!p5 && p6) a++
                    if (!p6 && p7) a++; if (!p7 && p8) a++; if (!p8 && p9) a++; if (!p9 && p2) a++
                    if (a != 1) continue
                    val ok = if (step == 0) !(p2 && p4 && p6) && !(p4 && p6 && p8)
                    else !(p2 && p4 && p8) && !(p2 && p6 && p8)
                    if (ok) {
                        if (n == buf.size) buf = buf.copyOf(n * 2)
                        buf[n++] = i
                    }
                }
                if (n > 0) {
                    changed = true
                    for (k in 0 until n) img[buf[k]] = false
                    var m = 0
                    for (k in 0 until np) { val i = pix[k]; if (img[i]) pix[m++] = i }
                    np = m
                }
            }
        }
    }

    /** Number of 0->1 transitions around the 8-ring (>=3 = junction, 1 = end point). */
    private fun crossings(img: BooleanArray, i: Int, w: Int): Int {
        val p2 = img[i - w]; val p3 = img[i - w + 1]; val p4 = img[i + 1]; val p5 = img[i + w + 1]
        val p6 = img[i + w]; val p7 = img[i + w - 1]; val p8 = img[i - 1]; val p9 = img[i - w - 1]
        var a = 0
        if (!p2 && p3) a++; if (!p3 && p4) a++; if (!p4 && p5) a++; if (!p5 && p6) a++
        if (!p6 && p7) a++; if (!p7 && p8) a++; if (!p8 && p9) a++; if (!p9 && p2) a++
        return a
    }

    private val DX = intArrayOf(1, 0, -1, 0, 1, -1, -1, 1)
    private val DY = intArrayOf(0, 1, 0, -1, 1, 1, -1, -1)

    // ------------------------------------------------------------------ skeleton graph

    private class IntList(cap: Int = 1024) {
        var a = IntArray(cap)
        var n = 0
        fun add(v: Int) { if (n == a.size) a = a.copyOf(n * 2); a[n++] = v }
    }

    private class Graph(val w: Int, val h: Int) {
        val pool = IntList(1 shl 14)
        val off = IntList(); val len = IntList()
        val c0 = IntList(); val c1 = IntList() // cluster ids at start/end, -1 = free end
        val nEdges get() = off.n

        companion object {
            fun build(img: BooleanArray, w: Int, h: Int): Graph {
                val g = Graph(w, h)
                val cluster = IntArray(w * h) { -1 }
                val visited = BooleanArray(w * h)
                val skel = IntList(1 shl 14)
                for (y in 1 until h - 1) for (x in 1 until w - 1) {
                    val i = y * w + x
                    if (img[i]) skel.add(i)
                }
                // junction clusters
                var nc = 0
                val stack = IntList()
                for (k in 0 until skel.n) {
                    val i = skel.a[k]
                    if (cluster[i] != -1 || crossings(img, i, w) < 3) continue
                    cluster[i] = nc
                    stack.n = 0; stack.add(i)
                    while (stack.n > 0) {
                        val p = stack.a[--stack.n]
                        for (d in 0 until 8) {
                            val q = p + DY[d] * w + DX[d]
                            if (img[q] && cluster[q] == -1 && crossings(img, q, w) >= 3) { cluster[q] = nc; stack.add(q) }
                        }
                    }
                    nc++
                }
                g.nClusters = nc
                // edges from junction pixels
                for (k in 0 until skel.n) {
                    val j = skel.a[k]
                    if (cluster[j] < 0) continue
                    for (d in 0 until 8) {
                        val n = j + DY[d] * w + DX[d]
                        if (img[n] && cluster[n] < 0 && !visited[n]) g.walk(img, cluster, visited, j, n)
                    }
                }
                // edges from end points
                for (k in 0 until skel.n) {
                    val i = skel.a[k]
                    if (visited[i] || cluster[i] >= 0) continue
                    if (crossings(img, i, w) <= 1) g.walk(img, cluster, visited, -1, i)
                }
                // closed loops
                for (k in 0 until skel.n) {
                    val i = skel.a[k]
                    if (visited[i] || cluster[i] >= 0) continue
                    g.walk(img, cluster, visited, -1, i)
                }
                return g
            }
        }

        var nClusters = 0

        /** Walk from [start] (junction pixel or -1) through [first]. */
        private fun walk(img: BooleanArray, cluster: IntArray, visited: BooleanArray, start: Int, first: Int) {
            val o = pool.n
            var endCluster = -1
            var prev = start
            var cur = first
            if (start >= 0) pool.add(start)
            while (true) {
                visited[cur] = true
                pool.add(cur)
                var next = -1
                var junction = -1
                for (d in 0 until 8) {
                    val q = cur + DY[d] * w + DX[d]
                    if (!img[q] || q == prev) continue
                    if (cluster[q] >= 0) { if (junction < 0 && !(start >= 0 && q == start)) junction = q; continue }
                    if (!visited[q] && next < 0) next = q
                }
                if (junction >= 0) {
                    pool.add(junction); endCluster = cluster[junction]; break
                }
                if (next < 0) break
                prev = cur
                cur = next
            }
            if (pool.n - o < 2 && start < 0) { pool.n = o; return }
            off.add(o); len.add(pool.n - o)
            c0.add(if (start >= 0) cluster[start] else -1)
            c1.add(endCluster)
        }

        /** Removes spurs (free end attached to junction) shorter than [spur] and loose fragments shorter than [minLen]. */
        fun prune(img: BooleanArray, spur: Int, minLen: Int, tex: TexMap?) {
            for (e in 0 until nEdges) {
                val l = len.a[e]
                val free0 = c0.a[e] < 0
                val free1 = c1.a[e] < 0
                val t = tex != null && l >= 2 && tex.at(pool.a[off.a[e] + l / 2])
                val kill = (free0 && free1 && l < (if (t) minLen * 2 else minLen)) ||
                    ((free0 != free1) && l < (if (t) spur * 3 else spur))
                if (!kill) continue
                val o = off.a[e]
                for (k in 0 until l) {
                    val p = pool.a[o + k]
                    // keep the junction-side pixel so the main line stays intact
                    if ((k == 0 && !free0) || (k == l - 1 && !free1)) continue
                    img[p] = false
                }
            }
        }

        /** Pairs edge ends at junctions by collinearity and returns pixel chains. */
        fun strokes(): List<IntArray> {
            val ne = nEdges
            val partner = IntArray(ne * 2) { -1 }
            if (nClusters > 0) {
                val byCluster = HashMap<Int, ArrayList<Int>>()
                for (e in 0 until ne) {
                    if (c0.a[e] >= 0) byCluster.getOrPut(c0.a[e]) { ArrayList() }.add(e * 2)
                    if (c1.a[e] >= 0) byCluster.getOrPut(c1.a[e]) { ArrayList() }.add(e * 2 + 1)
                }
                for ((_, ends) in byCluster) {
                    if (ends.size < 2) continue
                    val m = ends.size
                    val dirs = Array(m) { endDir(ends[it]) }
                    val pairs = ArrayList<Triple<Float, Int, Int>>()
                    for (a in 0 until m) for (b in a + 1 until m) {
                        val dot = dirs[a][0] * dirs[b][0] + dirs[a][1] * dirs[b][1]
                        if (dot < -0.72f) pairs.add(Triple(dot, a, b))
                    }
                    pairs.sortBy { it.first }
                    for ((_, a, b) in pairs) {
                        if (partner[ends[a]] >= 0 || partner[ends[b]] >= 0) continue
                        partner[ends[a]] = ends[b]; partner[ends[b]] = ends[a]
                    }
                }
            }
            val done = BooleanArray(ne)
            val res = ArrayList<IntArray>()
            val acc = IntList(256)
            fun follow(startEnd: Int) {
                acc.n = 0
                var end = startEnd
                while (true) {
                    val e = end shr 1
                    if (done[e]) break
                    done[e] = true
                    val o = off.a[e]; val l = len.a[e]
                    if (end and 1 == 0) for (k in 0 until l) acc.add(pool.a[o + k])
                    else for (k in l - 1 downTo 0) acc.add(pool.a[o + k])
                    val exit = end xor 1
                    val p = partner[exit]
                    if (p < 0) break
                    end = p
                }
                if (acc.n >= 2) res.add(acc.a.copyOf(acc.n))
            }
            for (e in 0 until ne) {
                if (done[e]) continue
                if (partner[e * 2] < 0) follow(e * 2)
                else if (partner[e * 2 + 1] < 0) follow(e * 2 + 1)
            }
            for (e in 0 until ne) if (!done[e]) follow(e * 2)
            return res
        }

        private fun endDir(end: Int): FloatArray {
            val e = end shr 1
            val o = off.a[e]; val l = len.a[e]
            val k = min(l - 1, 10)
            val a: Int; val b: Int
            if (end and 1 == 0) { a = pool.a[o]; b = pool.a[o + k] } else { a = pool.a[o + l - 1]; b = pool.a[o + l - 1 - k] }
            val dx = (b % w - a % w).toFloat(); val dy = (b / w - a / w).toFloat()
            val n = sqrt(dx * dx + dy * dy)
            return if (n < 1e-3f) floatArrayOf(0f, 0f) else floatArrayOf(dx / n, dy / n)
        }
    }

    // ------------------------------------------------------------------ gap bridging

    private fun bridge(img: BooleanArray, ink: FloatArray, w: Int, h: Int, gap: Float, lo: Float) {
        val ends = IntList()
        for (y in 2 until h - 2) for (x in 2 until w - 2) {
            val i = y * w + x
            if (img[i] && crossings(img, i, w) == 1) ends.add(i)
        }
        val gi = gap.toInt() + 1
        val own = IntArray(40)
        val lines = IntList()
        for (k in 0 until ends.n) {
            val e = ends.a[k]
            // walk back along the stroke to get direction + own pixels
            var nOwn = 0
            own[nOwn++] = e
            var prev = -1
            var cur = e
            while (nOwn < own.size) {
                var nx = -1
                for (d in 0 until 8) {
                    val q = cur + DY[d] * w + DX[d]
                    if (q < w || q >= w * (h - 1) || !img[q] || q == prev) continue
                    var seen = false
                    for (t in 0 until nOwn) if (own[t] == q) { seen = true; break }
                    if (!seen) { nx = q; break }
                }
                if (nx < 0) break
                own[nOwn++] = nx
                prev = cur; cur = nx
            }
            if (nOwn < 6) continue
            val ex = e % w; val ey = e / w
            val back = own[min(nOwn - 1, 12)]
            var dx = (ex - back % w).toFloat(); var dy = (ey - back / w).toFloat()
            val dn = sqrt(dx * dx + dy * dy)
            if (dn < 1e-3f) continue
            dx /= dn; dy /= dn
            var best = -1; var bestScore = Float.MAX_VALUE; var bestDist = 0f
            for (yy in max(1, ey - gi)..min(h - 2, ey + gi)) for (xx in max(1, ex - gi)..min(w - 2, ex + gi)) {
                val p = yy * w + xx
                if (!img[p]) continue
                val vx = (xx - ex).toFloat(); val vy = (yy - ey).toFloat()
                val dist = sqrt(vx * vx + vy * vy)
                if (dist < 1.5f || dist > gap) continue
                val cos = (vx * dx + vy * dy) / dist
                if (cos < 0.55f) continue
                var isOwn = false
                for (t in 0 until nOwn) if (own[t] == p) { isOwn = true; break }
                if (isOwn) continue
                val s = dist * (1.6f - cos)
                if (s < bestScore) { bestScore = s; best = p; bestDist = dist }
            }
            if (best < 0) continue
            // ink support along the bridge
            val bx = best % w; val by = best / w
            val steps = max(abs(bx - ex), abs(by - ey))
            var sum = 0f
            for (s in 0..steps) {
                val t = s.toFloat() / steps
                sum += ink[((ey + (by - ey) * t).toInt()) * w + (ex + (bx - ex) * t).toInt()]
            }
            if (bestDist > gap * 0.5f && sum / (steps + 1) < lo * 0.45f) continue
            for (s in 1 until steps) {
                val t = s.toFloat() / steps
                lines.add(((ey + Math.round((by - ey) * t))) * w + (ex + Math.round((bx - ex) * t)))
            }
        }
        for (k in 0 until lines.n) img[lines.a[k]] = true
    }

    // ------------------------------------------------------------------ geometry helpers

    private fun localMax(ink: FloatArray, w: Int, h: Int, p: Int): Float {
        val x = p % w; val y = p / w
        var m = 0f
        for (yy in max(0, y - 1)..min(h - 1, y + 1)) for (xx in max(0, x - 1)..min(w - 1, x + 1)) {
            val v = ink[yy * w + xx]; if (v > m) m = v
        }
        return m
    }

    private fun movingAverage(p: FloatArray, r: Int): FloatArray {
        val n = p.size / 2
        if (n < 3) return p
        val out = p.copyOf()
        for (i in 1 until n - 1) {
            val rr = min(r, min(i, n - 1 - i))
            var sx = 0f; var sy = 0f
            for (k in i - rr..i + rr) { sx += p[k * 2]; sy += p[k * 2 + 1] }
            val c = (2 * rr + 1).toFloat()
            out[i * 2] = sx / c; out[i * 2 + 1] = sy / c
        }
        return out
    }

    private fun polyLen(p: FloatArray): Float {
        var t = 0f
        var i = 2
        while (i < p.size) {
            val dx = p[i] - p[i - 2]; val dy = p[i + 1] - p[i - 1]
            t += sqrt(dx * dx + dy * dy); i += 2
        }
        return t
    }

    private fun rdp(points: FloatArray, epsilon: Float): FloatArray {
        val n = points.size / 2
        if (n < 3) return points
        val keep = BooleanArray(n)
        keep[0] = true; keep[n - 1] = true
        val stack = ArrayList<IntArray>()
        stack.add(intArrayOf(0, n - 1))
        while (stack.isNotEmpty()) {
            val r = stack.removeAt(stack.size - 1)
            val a = r[0]; val b = r[1]
            val ax = points[a * 2]; val ay = points[a * 2 + 1]
            val bx = points[b * 2]; val by = points[b * 2 + 1]
            val dx = bx - ax; val dy = by - ay
            val l = sqrt(dx * dx + dy * dy)
            var maxD = -1f; var idx = -1
            for (i in a + 1 until b) {
                val px = points[i * 2]; val py = points[i * 2 + 1]
                val dd = if (l < 1e-6f) sqrt((px - ax) * (px - ax) + (py - ay) * (py - ay))
                else abs(dy * px - dx * py + bx * ay - by * ax) / l
                if (dd > maxD) { maxD = dd; idx = i }
            }
            if (idx >= 0 && maxD > epsilon) {
                keep[idx] = true
                stack.add(intArrayOf(a, idx)); stack.add(intArrayOf(idx, b))
            }
        }
        var c = 0
        for (k in keep) if (k) c++
        val out = FloatArray(c * 2)
        var o = 0
        for (i in 0 until n) if (keep[i]) { out[o++] = points[i * 2]; out[o++] = points[i * 2 + 1] }
        return out
    }

    /** Corner-cutting subdivision; end points are preserved. */
    private fun chaikin(p: FloatArray): FloatArray {
        val n = p.size / 2
        if (n < 3) return p
        val out = FloatArray((2 * (n - 1) + 2) * 2)
        var o = 0
        out[o++] = p[0]; out[o++] = p[1]
        for (i in 0 until n - 1) {
            val ax = p[i * 2]; val ay = p[i * 2 + 1]; val bx = p[i * 2 + 2]; val by = p[i * 2 + 3]
            if (i > 0) { out[o++] = 0.75f * ax + 0.25f * bx; out[o++] = 0.75f * ay + 0.25f * by }
            if (i < n - 2) { out[o++] = 0.25f * ax + 0.75f * bx; out[o++] = 0.25f * ay + 0.75f * by }
        }
        out[o++] = p[(n - 1) * 2]; out[o++] = p[(n - 1) * 2 + 1]
        return out.copyOf(o)
    }

    // ------------------------------------------------------------------ ordering

    private fun order(c: List<Cand>): List<Cand> {
        if (c.size < 2) return c
        val sorted = c.sortedByDescending { it.len }
        val nMajor = min(sorted.size, max(1, min(24, sorted.size / 12)))
        val result = ArrayList<Cand>(c.size)
        for (i in 0 until nMajor) result.add(sorted[i])
        val rest = ArrayList(sorted.subList(nMajor, sorted.size))
        // top-to-bottom bucket sort of the remainder keeps NN search local
        var cx: Float; var cy: Float
        val last = result.last().pts
        cx = last[last.size - 2]; cy = last[last.size - 1]
        val n = rest.size
        val used = BooleanArray(n)
        for (step in 0 until n) {
            var best = -1; var bd = Float.MAX_VALUE; var rev = false
            for (i in 0 until n) {
                if (used[i]) continue
                val p = rest[i].pts
                val sx = p[0] - cx; val sy = p[1] - cy
                val ex = p[p.size - 2] - cx; val ey = p[p.size - 1] - cy
                val ds = sx * sx + sy * sy; val de = ex * ex + ey * ey
                if (ds < bd) { bd = ds; best = i; rev = false }
                if (de < bd) { bd = de; best = i; rev = true }
            }
            used[best] = true
            val s = rest[best]
            if (rev) s.pts = reversePts(s.pts)
            result.add(s)
            cx = s.pts[s.pts.size - 2]; cy = s.pts[s.pts.size - 1]
        }
        return result
    }

    private fun reversePts(p: FloatArray): FloatArray {
        val n = p.size / 2
        val o = FloatArray(p.size)
        for (i in 0 until n) { o[i * 2] = p[(n - 1 - i) * 2]; o[i * 2 + 1] = p[(n - 1 - i) * 2 + 1] }
        return o
    }
}
