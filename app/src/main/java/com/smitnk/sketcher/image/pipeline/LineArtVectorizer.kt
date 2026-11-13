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

    fun vectorize(ink: FloatArray, w: Int, h: Int, detail: Float, lineWidth: Float, maxStrokes: Int): List<Stroke> {
        if (w < 8 || h < 8 || ink.size < w * h || maxStrokes <= 0) return emptyList()
        val d = detail.coerceIn(0f, 1f)
        val sc = (max(w, h) / 768f).coerceIn(0.5f, 3f)

                val lo = 0.22f - 0.12f * d
        val hi = 0.46f - 0.20f * d
        var T=System.nanoTime(); fun tick(n:String){val t=System.nanoTime();System.err.println(n+" "+(t-T)/1000000);T=t}
        val mask = dilate(hysteresis(ink, w, h, lo, hi, (6 * sc * sc).toInt().coerceAtLeast(4)), w, h)
        // clear border so neighbour access never goes out of range
        for (x in 0 until w) { mask[x] = false; mask[(h - 1) * w + x] = false }
        for (y in 0 until h) { mask[y * w] = false; mask[y * w + w - 1] = false }
        tick("hyst"); thin(mask, w, h); tick("thin")

        // prune spurs & tiny fragments, bridge gaps, rebuild
        val spurLen = (7f * sc).toInt()
        val minLen = (10f * sc).toInt()
        var g = Graph.build(mask, w, h)
        tick("graph1"); g.prune(mask, spurLen, minLen)
        bridge(mask, ink, w, h, (6f + 7f * d) * sc, lo)
        tick("bridge"); g = Graph.build(mask, w, h)
        tick("graph2"); val chains = g.strokes()
        tick("strokes")

        // ---- chains -> smooth strokes
        val cands = ArrayList<Cand>(chains.size)
        val eps = 0.55f
        val minScore = (16f - 11f * d) * sc
        for (c in chains) {
            val n = c.size
            if (n < 4) continue
            var sum = 0f
            for (i in 0 until n) sum += localMax(ink, w, h, c[i])
            val mean = sum / n
            var pts = FloatArray(n * 2)
            for (i in 0 until n) { pts[i * 2] = (c[i] % w) + 0.5f; pts[i * 2 + 1] = (c[i] / w) + 0.5f }
            pts = movingAverage(pts, 3)
            pts = rdp(pts, eps)
            pts = chaikin(chaikin(pts))
            val len = polyLen(pts)
            if (len < 5f * sc) continue
            val score = len * mean
            if (score < minScore || mean < 0.20f - 0.06f * d) continue
            cands.add(Cand(pts, len, mean, score))
        }
        tick("smooth"); var kept: List<Cand> = cands
        if (kept.size > maxStrokes) kept = kept.sortedByDescending { it.score }.take(maxStrokes)

        val ordered = order(kept)
        tick("order"); val out = ArrayList<Stroke>(ordered.size)
        for (c in ordered) {
            val s = ((c.mean - 0.15f) / 0.4f).coerceIn(0f, 1f)
            out.add(Stroke(c.pts, 0xFF000000.toInt(), lineWidth * (0.6f + 1.0f * s), BrushType.PEN, 1f))
        }
        return out
    }

    private class Cand(var pts: FloatArray, val len: Float, val mean: Float, val score: Float)

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
        var changed = true
        while (changed) {
            changed = false
            for (step in 0..1) {
                var n = 0
                for (y in 1 until h - 1) {
                    var i = y * w + 1
                    for (x in 1 until w - 1) {
                        if (img[i]) {
                            val p2 = img[i - w]; val p3 = img[i - w + 1]; val p4 = img[i + 1]; val p5 = img[i + w + 1]
                            val p6 = img[i + w]; val p7 = img[i + w - 1]; val p8 = img[i - 1]; val p9 = img[i - w - 1]
                            var b = 0
                            if (p2) b++; if (p3) b++; if (p4) b++; if (p5) b++
                            if (p6) b++; if (p7) b++; if (p8) b++; if (p9) b++
                            if (b in 2..6) {
                                var a = 0
                                if (!p2 && p3) a++; if (!p3 && p4) a++; if (!p4 && p5) a++; if (!p5 && p6) a++
                                if (!p6 && p7) a++; if (!p7 && p8) a++; if (!p8 && p9) a++; if (!p9 && p2) a++
                                if (a == 1) {
                                    val ok = if (step == 0) !(p2 && p4 && p6) && !(p4 && p6 && p8)
                                    else !(p2 && p4 && p8) && !(p2 && p6 && p8)
                                    if (ok) {
                                        if (n == buf.size) buf = buf.copyOf(n * 2)
                                        buf[n++] = i
                                    }
                                }
                            }
                        }
                        i++
                    }
                }
                if (n > 0) {
                    changed = true
                    for (k in 0 until n) img[buf[k]] = false
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
        fun prune(img: BooleanArray, spur: Int, minLen: Int) {
            for (e in 0 until nEdges) {
                val l = len.a[e]
                val free0 = c0.a[e] < 0
                val free1 = c1.a[e] < 0
                val kill = (free0 && free1 && l < minLen) || ((free0 != free1) && l < spur)
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
            var best = -1; var bestScore = Float.MAX_VALUE
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
                if (s < bestScore) { bestScore = s; best = p }
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
            if (sum / (steps + 1) < lo * 0.45f) continue
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
