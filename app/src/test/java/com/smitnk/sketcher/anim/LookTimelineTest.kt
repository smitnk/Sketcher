package com.smitnk.sketcher.anim

import com.smitnk.sketcher.look.LookSettings
import com.smitnk.sketcher.look.StrokeFeel
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LookTimelineTest {
    private val feels = listOf(StrokeFeel.CLEAN, StrokeFeel.WHITEBOARD_MARKER, StrokeFeel.PEN_PLOTTER, StrokeFeel.SCRATCH_BOIL)

    private fun layers(seed: Long): List<DrawTimeline.LayerSpec> {
        val r = Random(seed)
        fun spec(id: Long, n: Int, mean: Float, mul: Float): DrawTimeline.LayerSpec {
            val len = FloatArray(n) { (3 + r.nextFloat() * mean) }
            val sx = FloatArray(n) { r.nextFloat() * 1000 }; val sy = FloatArray(n) { r.nextFloat() * 1000 }
            return DrawTimeline.LayerSpec(id, len, sx, sy, FloatArray(n) { sx[it] + len[it] * 0.7f }, FloatArray(n) { sy[it] }, FloatArray(n) { mul })
        }
        return listOf(spec(1, 200, 150f, 1f), spec(2, 80, 60f, 2.5f))
    }

    @Test fun easingEndpointsAndMonotonic() {
        for (r in listOf(0.05, 0.2, 0.5)) {
            assertEquals(0.0, DrawTimeline.easeTrapezoid(0.0, r), 1e-12)
            assertEquals(1.0, DrawTimeline.easeTrapezoid(1.0, r), 1e-12)
        }
        assertEquals(0.0, DrawTimeline.easeWhiteboard(0.0), 1e-12)
        assertEquals(1.0, DrawTimeline.easeWhiteboard(1.0), 1e-12)
        var a = -1.0; var b = -1.0; var c = -1.0
        for (i in 0..1000) {
            val u = i / 1000.0
            val w = DrawTimeline.easeWhiteboard(u); val t = DrawTimeline.easeTrapezoid(u, 0.2); val t2 = DrawTimeline.easeTrapezoid(u, 0.5)
            assertTrue(w >= a); assertTrue(t >= b); assertTrue(t2 >= c); a = w; b = t; c = t2
        }
        // slow-fast-slow: early progress is behind linear, mid is steeper
        assertTrue(DrawTimeline.easeWhiteboard(0.1) < 0.1)
        assertTrue(DrawTimeline.easeTrapezoid(0.1, 0.2) < 0.1)
        // continuity of the trapezoid at ramp joins
        assertEquals(DrawTimeline.easeTrapezoid(0.2 - 1e-9, 0.2), DrawTimeline.easeTrapezoid(0.2 + 1e-9, 0.2), 1e-6)
    }

    @Test fun trapezoidTimeAndRamp() {
        val v = 500.0; val a = DrawTimeline.PLOT_ACCEL
        val short = 20.0; val long = 2000.0
        assertEquals(2 * Math.sqrt(short / a), DrawTimeline.trapezoidTime(short, v, a), 1e-9)
        assertEquals(long / v + v / a, DrawTimeline.trapezoidTime(long, v, a), 1e-9)
        assertEquals(0.5, DrawTimeline.trapezoidRamp(short, v, a), 1e-9)
        assertTrue(DrawTimeline.trapezoidRamp(long, v, a) < 0.1)
    }

    @Test fun everyFeelRevealsMonotonicallyAndEndsExactly() {
        for (f in feels) {
            val ls = layers(3)
            val t = DrawTimeline.build(ls, f)
            val total = ls.sumOf { l -> l.lengths.sumOf { it.toDouble() } }
            var prev = -1.0
            for (i in 0..3000) {
                val v = t.allowances(i / 3000f).values.sumOf { it.toDouble() } / total
                assertTrue("$f at $i", v >= prev - 1e-9); prev = v
            }
            assertEquals(0.0, t.allowances(0f).values.sum().toDouble(), 0.0)
            assertEquals(1.0, t.allowances(1f).values.sumOf { it.toDouble() } / total, 1e-6)
            assertEquals(1.0, t.endOf(t.strokeCount - 1), 0.0)
        }
    }

    @Test fun noTailPopAndPauseCap() {
        for (f in feels) {
            val ls = layers(5)
            val t = DrawTimeline.build(ls, f)
            val total = ls.sumOf { l -> l.lengths.sumOf { it.toDouble() } }
            // the last 2% of time must not hold more than a few percent of the length (no pop at the end)
            val v98 = t.allowances(0.98f).values.sumOf { it.toDouble() } / total
            assertTrue("$f v98=$v98", v98 > 0.85)
            // pauses (gaps between a stroke end and next start) cap
            var gaps = 0.0
            for (i in 1 until t.strokeCount) gaps += t.startOf(i) - t.endOf(i - 1)
            val cap = if (f == StrokeFeel.PEN_PLOTTER) DrawTimeline.PLOT_MAX_PAUSE_FRACTION else DrawTimeline.MAX_PAUSE_FRACTION
            assertTrue("$f gaps=$gaps", gaps <= cap + 1e-6)
        }
    }

    @Test fun plotterTravelTimeGrowsWithDistance() {
        fun total(dx: Float): Double {
            val l = DrawTimeline.LayerSpec(1, floatArrayOf(50f, 50f), floatArrayOf(0f, dx), floatArrayOf(0f, 0f), floatArrayOf(50f, dx + 50f), floatArrayOf(0f, 0f), floatArrayOf(1f, 1f))
            return DrawTimeline.build(listOf(l), StrokeFeel.PEN_PLOTTER).rawSeconds
        }
        assertTrue(total(1000f) > total(60f))
    }

    @Test fun onTwosQuantisesTo12PerSecondAndKeepsEnd() {
        val look = LookSettings(onTwos = true)
        val dur = 5f
        assertEquals(1f, LookTiming.effectiveProgress(1f, dur, look), 0f)
        assertEquals(0f, LookTiming.effectiveProgress(0f, dur, look), 0f)
        val steps = HashSet<Float>()
        var prev = 0f
        for (i in 0..5000) {
            val e = LookTiming.effectiveProgress(i / 5000f, dur, look)
            assertTrue(e >= prev && e <= i / 5000f + 1e-6f); prev = e; steps.add(e)
        }
        assertTrue(steps.size in 60..62)
        assertEquals(0.3f, LookTiming.effectiveProgress(0.3f + 1e-4f, 5f, look), 1e-4f)
        // off => identity
        assertEquals(0.37f, LookTiming.effectiveProgress(0.37f, dur, LookSettings()), 0f)
    }

    @Test fun boilDrawingIndexAdvancesWithTimeOnlyWhenBoiling() {
        val boil = LookSettings(feel = StrokeFeel.SCRATCH_BOIL)
        assertEquals(0, LookTiming.drawingIndex(0.5f, 10f, LookSettings()))
        assertEquals(0, LookTiming.drawingIndex(0f, 10f, boil))
        assertEquals(60, LookTiming.drawingIndex(0.5f, 10f, boil))
        assertEquals(LookTiming.drawingIndex(0.5f, 10f, boil), LookTiming.drawingIndex(0.5f + 0.001f, 10f, boil))
    }
}
