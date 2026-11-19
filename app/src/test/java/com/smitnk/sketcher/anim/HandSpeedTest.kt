package com.smitnk.sketcher.anim

import com.smitnk.sketcher.look.StrokeFeel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class HandSpeedTest {
    private fun circle(r: Float, n: Int = 64): FloatArray {
        val p = FloatArray(n * 2)
        for (i in 0 until n) { val a = 2 * PI * i / (n - 1); p[2 * i] = (r * cos(a)).toFloat(); p[2 * i + 1] = (r * sin(a)).toFloat() }
        return p
    }
    private fun len(p: FloatArray): Float { var s = 0f; var i = 2; while (i < p.size) { s += Math.hypot((p[i] - p[i - 2]).toDouble(), (p[i + 1] - p[i - 1]).toDouble()).toFloat(); i += 2 }; return s }

    @Test fun tightCurvesAreSlowerAndClamped() {
        val tight = circle(10f); val loose = circle(80f); val wide = circle(2000f)
        val line = floatArrayOf(0f, 0f, 50f, 0f, 100f, 0f, 150f, 0f)
        val a = HandSpeed.curvatureMul(tight, len(tight)); val b = HandSpeed.curvatureMul(loose, len(loose))
        assertTrue(a < b)
        assertEquals(1f, b, 0.05f)                       // reference radius -> ~1
        assertEquals(0.5f, a / b, 0.05f)                  // v ~ r^(1/3): (10/80)^(1/3) = 0.5
        assertEquals(HandSpeed.MAX_MUL, HandSpeed.curvatureMul(line, 150f), 0f)
        assertEquals(HandSpeed.MAX_MUL, HandSpeed.curvatureMul(wide, len(wide)), 0f)
        assertEquals(HandSpeed.MIN_MUL, HandSpeed.curvatureMul(circle(0.5f), len(circle(0.5f))), 0f)
    }

    @Test fun jitterIsSeededAndBounded() {
        var lo = 9f; var hi = 0f
        for (i in 0 until 5000) { val j = HandSpeed.jitter(i.toLong()); lo = minOf(lo, j); hi = maxOf(hi, j); assertEquals(j, HandSpeed.jitter(i.toLong()), 0f) }
        assertTrue(lo >= 1f - HandSpeed.JITTER - 1e-6f && hi <= 1f + HandSpeed.JITTER + 1e-6f)
        assertTrue(hi - lo > 0.12f) // actually spreads
    }

    private fun spec(hand: FloatArray?, vararg lens: Float): DrawTimeline.LayerSpec {
        var x = 0f
        val n = lens.size
        val sx = FloatArray(n); val ex = FloatArray(n)
        for (i in 0 until n) { sx[i] = x + 300f; x += lens[i] + 300f; ex[i] = x - 300f + 300f }
        return DrawTimeline.LayerSpec(1, lens, sx, FloatArray(n), ex, FloatArray(n), FloatArray(n) { 1f }, handSpeed = hand)
    }

    @Test fun slowStrokeTakesLongerAndOverlapShortensPauses() {
        val plain = DrawTimeline.build(listOf(spec(null, 300f, 300f, 300f)))
        val slow = DrawTimeline.build(listOf(spec(floatArrayOf(1f, 0.5f, 1f), 300f, 300f, 300f)))
        val d = { t: DrawTimeline.Timeline, i: Int -> (t.endOf(i) - t.startOf(i)) * t.rawSeconds }
        assertTrue(d(slow, 1) > d(slow, 0) * 1.5) // half speed on the middle stroke
        // contiguous strokes: pause between = lift - 80 ms, never negative
        val fast = DrawTimeline.build(listOf(spec(floatArrayOf(1f, 1f, 1f), 300f, 300f, 300f)))
        val gapPlain = (plain.startOf(1) - plain.endOf(0)) * plain.rawSeconds
        val gapHuman = (fast.startOf(1) - fast.endOf(0)) * fast.rawSeconds
        assertEquals(DrawTimeline.OVERLAP_SEC, gapPlain - gapHuman, 1e-6)
    }

    @Test fun plotterAndPencilIgnoreHandSpeed() {
        val a = DrawTimeline.build(listOf(spec(null, 100f, 200f)), StrokeFeel.PEN_PLOTTER)
        val b = DrawTimeline.build(listOf(spec(floatArrayOf(0.6f, 1.2f), 100f, 200f)), StrokeFeel.PEN_PLOTTER)
        // plotter keeps its trapezoid and pen-up pauses: handSpeed does not change the plotted strokes' durations
        assertEquals(a.rawSeconds, b.rawSeconds, 1e-9)
        assertEquals(a.startOf(1), b.startOf(1), 1e-12)
    }
}
