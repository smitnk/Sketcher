package com.smitnk.sketcher.anim

import java.util.Random
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Realistic pencil-reveal drawing: 300 outline strokes + 168 coverage hairs (+ optional fast shading layer). */
class DrawTailTest {
    private class Spec(val l: DrawTimeline.LayerSpec)

    private fun strokes(rnd: Random, n: Int, mean: Float, near: Boolean): List<FloatArray> {
        var cx = 500f; var cy = 500f
        return List(n) {
            val len = (-ln(1.0 - rnd.nextDouble()) * mean).toFloat().coerceAtLeast(3f)
            val sx = if (near) cx + rnd.nextFloat() * 60 - 30 else rnd.nextFloat() * 1024
            val sy = if (near) cy + rnd.nextFloat() * 60 - 30 else rnd.nextFloat() * 1024
            val a = rnd.nextDouble() * 6.28
            val ex = sx + (cos(a) * len * 0.7).toFloat(); val ey = sy + (sin(a) * len * 0.7).toFloat()
            cx = ex; cy = ey
            floatArrayOf(len, sx, sy, ex, ey)
        }
    }

    private fun spec(id: Long, s: List<FloatArray>, mul: Float) = DrawTimeline.LayerSpec(
        id, FloatArray(s.size) { s[it][0] }, FloatArray(s.size) { s[it][1] }, FloatArray(s.size) { s[it][2] },
        FloatArray(s.size) { s[it][3] }, FloatArray(s.size) { s[it][4] }, FloatArray(s.size) { mul },
    )

    private fun scenario(seed: Long, interleave: Boolean, shading: Boolean): List<DrawTimeline.LayerSpec> {
        val r = Random(seed)
        val base = strokes(r, 300, 90f, true)
        val cover = strokes(r, 168, 8f, false)
        val outline = if (interleave) StrokeInterleave.interleave(base, cover) { floatArrayOf(it[1], it[2], it[3], it[4]) } else base + cover
        val out = mutableListOf(spec(1, outline, 1f))
        if (shading) out.add(spec(2, strokes(r, 150, 60f, true), 2.5f))
        return out
    }

    private class Curve(layers: List<DrawTimeline.LayerSpec>) {
        val t = DrawTimeline.build(layers)
        val total = layers.sumOf { l -> l.lengths.sumOf { it.toDouble() } }
        fun rev(p: Float) = t.allowances(p).values.sumOf { it.toDouble() } / total
    }

    @Test fun revealMonotonicAndExactAtEnd() {
        for (shade in listOf(false, true)) {
            val c = Curve(scenario(1, true, shade))
            var prev = -1.0
            for (i in 0..2000) { val v = c.rev(i / 2000f); assertTrue(v >= prev - 1e-9); prev = v }
            assertEquals(0.0, c.rev(0f), 0.0)
            assertEquals(1.0, c.rev(1f), 1e-6)
            assertEquals(1.0, c.t.endOf(c.t.strokeCount - 1), 0.0)
        }
    }

    @Test fun noEarlyFinishAndNoPopAtTheEnd() {
        for (seed in 1L..4L) for (shade in listOf(false, true)) {
            val c = Curve(scenario(seed, true, shade))
            assertTrue("0.97 reached too early (seed $seed shade $shade)", c.rev(0.95f) < 0.97)
            val last10 = 1.0 - c.rev(0.9f)
            assertTrue("last 10% of time reveals only ${last10 * 100}%", last10 >= 0.05)
            // no single frame (60 fps, 12 s) jumps more than 1.5% of the drawing near the end
            var prev = c.rev(0.9f)
            for (i in 1..72) { val v = c.rev(0.9f + 0.1f * i / 72f); assertTrue(v - prev < 0.015); prev = v }
        }
    }

    @Test fun interleavingFixesTheCoverageTail() {
        val before = Curve(scenario(7, false, false))
        val after = Curve(scenario(7, true, false))
        val b = 1.0 - before.rev(0.9f); val a = 1.0 - after.rev(0.9f)
        assertTrue("before=$b after=$a", a > b * 3 && a >= 0.05)
    }

    @Test fun pausesNeverExceedQuarterOfTimeline() {
        // far-apart strokes: maximal pen lifts
        val n = 100
        val l = DrawTimeline.LayerSpec(1, FloatArray(n) { 5f }, FloatArray(n) { (it % 2) * 5000f }, FloatArray(n),
            FloatArray(n) { (it % 2) * 5000f }, FloatArray(n), FloatArray(n) { 1f })
        val t = DrawTimeline.build(listOf(l))
        var drawn = 0.0
        for (i in 0 until n) drawn += t.endOf(i) - t.startOf(i)
        assertTrue(1.0 - drawn <= DrawTimeline.MAX_PAUSE_FRACTION + 1e-6)
    }

    @Test fun interleavePlacesHairsNextToNeighbourAndKeepsAll() {
        val base = listOf(floatArrayOf(0f, 0f, 10f, 0f), floatArrayOf(500f, 500f, 510f, 500f), floatArrayOf(1000f, 0f, 1010f, 0f))
        val extra = listOf(floatArrayOf(505f, 505f), floatArrayOf(2f, 3f), floatArrayOf(1005f, 2f))
        val out = StrokeInterleave.interleave(base, extra) { it }
        assertEquals(6, out.size)
        assertTrue(out[1] === extra[1])
        assertTrue(out[3] === extra[0])
        assertTrue(out[5] === extra[2])
    }
}
