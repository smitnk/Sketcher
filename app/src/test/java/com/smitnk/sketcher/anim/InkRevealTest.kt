package com.smitnk.sketcher.anim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Visible-ink linearity of the pencil reveal, measured on a synthetic ink map. */
class InkRevealTest {
    private val W = 240
    private val H = 240
    private val R = 4f

    private fun line(x0: Float, y0: Float, x1: Float, y1: Float): FloatArray {
        val n = max(2, (hypot(x1 - x0, y1 - y0) / 6f).toInt())
        val p = FloatArray(2 * (n + 1))
        for (i in 0..n) { p[2 * i] = x0 + (x1 - x0) * i / n; p[2 * i + 1] = y0 + (y1 - y0) * i / n }
        return p
    }

    private fun len(p: FloatArray): Float {
        var t = 0f; var i = 2
        while (i < p.size) { t += hypot(p[i] - p[i - 2], p[i + 1] - p[i - 1]); i += 2 }
        return t
    }

    /** Ink: 10 horizontal lines, but dense where it matters least (ink is NOT spread like path length). */
    private fun scene(): Triple<ByteArray, List<FloatArray>, Int> {
        val ink = ByteArray(W * H)
        val strokes = ArrayList<FloatArray>()
        // 4 long wandering strokes over bare paper (no ink at all): lots of length, nothing to reveal
        for (k in 0 until 4) strokes.add(line(10f, 10f + k, 230f, 10f + k + 2f).let { it })
        // 10 ink lines, increasing thickness (so later strokes carry more ink)
        for (k in 0 until 10) {
            val y = 30 + k * 20
            val half = 1 + k / 4
            for (yy in y - half..y + half) for (x in 20..220) ink[yy * W + x] = 255.toByte()
            strokes.add(line(20f, y.toFloat(), 220f, y.toFloat()))
        }
        // 60 tiny strokes over already-revealed ink (nothing new)
        for (k in 0 until 60) strokes.add(line(30f + k * 2f, 30f, 36f + k * 2f, 30f))
        return Triple(ink, strokes, 0)
    }

    private fun visibleCurve(ink: ByteArray, strokes: List<FloatArray>, tl: DrawTimeline.Timeline, steps: Int, unitLens: List<FloatArray>): DoubleArray {
        val total = ink.count { (it.toInt() and 255) >= InkReveal.INK_THRESHOLD }.toDouble()
        val out = DoubleArray(steps + 1)
        for (s in 0..steps) {
            val a = tl.allowances(s / steps.toFloat())[1L]!!
            val covered = BooleanArray(W * H)
            var rem = a
            for (p in unitLens) {
                if (rem <= 0f) break
                val L = len(p)
                stampPartial(covered, p, min(rem, L))
                rem -= L
            }
            var v = 0
            for (i in 0 until W * H) if (covered[i] && (ink[i].toInt() and 255) >= InkReveal.INK_THRESHOLD) v++
            out[s] = v / total
        }
        return out
    }

    private fun stampPartial(c: BooleanArray, p: FloatArray, limit: Float) {
        var rem = limit
        fun disc(fx: Float, fy: Float) {
            for (y in (fy - R).toInt()..(fy + R).toInt()) for (x in (fx - R).toInt()..(fx + R).toInt()) {
                if (x in 0 until W && y in 0 until H && hypot(x - fx, y - fy) <= R) c[y * W + x] = true
            }
        }
        disc(p[0], p[1])
        var i = 2
        while (i < p.size && rem > 0f) {
            val dx = p[i] - p[i - 2]; val dy = p[i + 1] - p[i - 1]; val d = hypot(dx, dy)
            val use = min(d, rem)
            val n = max(1, (use / 1f).toInt())
            for (s in 1..n) disc(p[i - 2] + dx / d * use * s / n, p[i - 1] + dy / d * use * s / n)
            rem -= use; i += 2
        }
    }

    private fun plan(ink: ByteArray, strokes: List<FloatArray>, weighted: Boolean): Pair<DrawTimeline.Timeline, List<FloatArray>> {
        if (!weighted) {
            val n = strokes.size
            val spec = DrawTimeline.LayerSpec(1L, FloatArray(n) { len(strokes[it]) },
                FloatArray(n) { strokes[it][0] }, FloatArray(n) { strokes[it][1] },
                FloatArray(n) { strokes[it][strokes[it].size - 2] }, FloatArray(n) { strokes[it][strokes[it].size - 1] }, FloatArray(n) { 1f })
            return DrawTimeline.build(listOf(spec)) to strokes
        }
        val pieces = ArrayList<FloatArray>(); val lens = ArrayList<Float>()
        for (s in strokes) { val c = InkReveal.chunk(s, len(s), 40f); pieces.addAll(c.points); for (x in c.lengths) lens.add(x) }
        val n = pieces.size
        val l = lens.toFloatArray()
        val dur = InkReveal.durations(l, InkReveal.newInkPerPiece(ink, W, H, pieces, R), l.sumOf { it.toDouble() } / DrawTimeline.SPEED_PX_PER_SEC)
        val spec = DrawTimeline.LayerSpec(1L, l, FloatArray(n) { pieces[it][0] }, FloatArray(n) { pieces[it][1] },
            FloatArray(n) { pieces[it][pieces[it].size - 2] }, FloatArray(n) { pieces[it][pieces[it].size - 1] }, FloatArray(n) { 1f }, dur)
        return DrawTimeline.build(listOf(spec)) to pieces
    }

    private fun maxDev(c: DoubleArray): Double {
        val n = c.size - 1
        var m = 0.0
        for (i in 0..n) { val t = i / n.toDouble(); if (t in 0.05..0.95) m = max(m, abs(c[i] - t)) }
        return m
    }

    @Test fun visibleInkIsLinearInTime() {
        val (ink, strokes, _) = scene()
        val (tl, pieces) = plan(ink, strokes, true)
        val c = visibleCurve(ink, strokes, tl, 80, pieces)
        assertTrue("max deviation from linear ${maxDev(c)}", maxDev(c) <= 0.12)
        assertTrue("last 10% of time must add >= 7% ink, added ${c[80] - c[72]}", c[80] - c[72] >= 0.07)
        assertEquals(1.0, c[80], 0.01)
    }

    @Test fun lengthPacingFailsTheSameMetric() {
        // documents the bug this fixes: path-length pacing front-loads/stalls on the same scene
        val (ink, strokes, _) = scene()
        val (tl, pieces) = plan(ink, strokes, false)
        val c = visibleCurve(ink, strokes, tl, 80, pieces)
        assertTrue("old pacing should violate the target (dev ${maxDev(c)}, tail ${c[80] - c[72]})", maxDev(c) > 0.12 || c[80] - c[72] < 0.07)
    }

    @Test fun noStallsWhileInkRemains() {
        val (ink, strokes, _) = scene()
        val (tl, pieces) = plan(ink, strokes, true)
        val c = visibleCurve(ink, strokes, tl, 80, pieces)
        for (i in 1..76) assertTrue("stall at step $i", c[i + 4] - c[i] > 0.02) // any 5% of time shows >2% more ink
    }

    @Test fun chunkingKeepsExactLength() {
        val p = line(0f, 0f, 500f, 120f)
        val total = len(p)
        val c = InkReveal.chunk(p, total, 70f)
        assertTrue(c.points.size > 4)
        assertEquals(total, c.lengths.sum(), 1e-3f)
        for (i in 1 until c.points.size) { // pieces share end vertices
            val a = c.points[i - 1]; val b = c.points[i]
            assertEquals(a[a.size - 2], b[0], 0f); assertEquals(a[a.size - 1], b[1], 0f)
        }
        assertEquals(1, InkReveal.chunk(floatArrayOf(0f, 0f, 5f, 0f), 5f, 70f).points.size)
    }

    @Test fun durationsSumAndRespectSpeedCap() {
        val lens = floatArrayOf(1000f, 10f, 10f, 500f)
        val ink = floatArrayOf(0f, 100f, 0f, 400f)
        val d = InkReveal.durations(lens, ink, 3.0)
        assertEquals(3.0, d.sum(), 1e-9)
        for (i in lens.indices) assertTrue(lens[i] / d[i] <= InkReveal.MAX_SPEED * 1.001)
        assertTrue(d[3] > d[1])
    }

    @Test fun layerPausesOnlyAtLayerStart() {
        val (ink, strokes, _) = scene()
        val (tl, _) = plan(ink, strokes, true)
        for (i in 1 until tl.strokeCount) assertEquals(tl.endOf(i - 1), tl.startOf(i), 1e-12)
    }

    @Test fun newInkCountsEachPixelOnce() {
        val ink = ByteArray(W * H) { 255.toByte() }
        val a = floatArrayOf(50f, 50f, 150f, 50f)
        val counts = InkReveal.newInkPerPiece(ink, W, H, listOf(a, a), R)
        assertTrue(counts[0] > 500f)
        assertEquals(0f, counts[1], 0f)
    }
}
