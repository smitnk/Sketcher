package com.smitnk.sketcher.anim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawTimelineTest {
    private fun layer(id: Long, mul: Float, vararg lens: Float): DrawTimeline.LayerSpec {
        // strokes laid out left to right along y=0, each starting where the previous ended
        var x = 0f
        val sx = FloatArray(lens.size); val ex = FloatArray(lens.size)
        for (i in lens.indices) { sx[i] = x; x += lens[i]; ex[i] = x }
        return DrawTimeline.LayerSpec(id, lens, sx, FloatArray(lens.size), ex, FloatArray(lens.size), FloatArray(lens.size) { mul })
    }

    @Test fun endpointsAreExact() {
        val t = DrawTimeline.build(listOf(layer(1, 1f, 100f, 200f), layer(2, 1f, 50f)))
        val a0 = t.allowances(0f)
        assertEquals(0f, a0[1]!!, 0f); assertEquals(0f, a0[2]!!, 0f)
        val a1 = t.allowances(1f)
        assertEquals(300f, a1[1]!!, 0f); assertEquals(50f, a1[2]!!, 0f)
    }

    @Test fun monotonicAndLayerOrder() {
        val t = DrawTimeline.build(listOf(layer(1, 1f, 100f, 200f), layer(2, 1f, 50f, 60f)))
        var prev1 = 0f; var prev2 = 0f
        for (i in 0..1000) {
            val a = t.allowances(i / 1000f)
            assertTrue(a[1]!! >= prev1 && a[2]!! >= prev2)
            if (a[2]!! > 0f) assertEquals(300f, a[1]!!, 0f)
            prev1 = a[1]!!; prev2 = a[2]!!
        }
    }

    @Test fun pauseBetweenStrokesHoldsLength() {
        val t = DrawTimeline.build(listOf(layer(1, 1f, 100f, 100f)))
        val mid = (t.endOf(0) + t.startOf(1)) / 2
        assertTrue(t.startOf(1) > t.endOf(0))
        assertEquals(100f, t.allowances(mid.toFloat())[1]!!, 0.01f)
    }

    @Test fun layerPauseLongerThanLift() {
        val t = DrawTimeline.build(listOf(layer(1, 1f, 100f, 100f), layer(2, 1f, 100f)))
        val lift = (t.startOf(1) - t.endOf(0))
        val layerGap = (t.startOf(2) - t.endOf(1))
        assertTrue(layerGap > lift)
    }

    @Test fun easeInOutWithinStroke() {
        val t = DrawTimeline.build(listOf(layer(1, 1f, 1000f)))
        val q = t.allowances(0.1f)[1]!!   // first 10% of time: slower than uniform
        val h = t.allowances(0.5f)[1]!!
        assertTrue(q < 100f)
        assertEquals(500f, h, 1f)
        assertTrue(t.allowances(0.9f)[1]!! > 900f)
    }

    @Test fun shadingFasterThanOutline() {
        val o = DrawTimeline.build(listOf(layer(1, 1f, 500f)))
        val s = DrawTimeline.build(listOf(layer(1, 2.5f, 500f)))
        val f = DrawTimeline.build(listOf(layer(1, 3f, 500f)))
        assertEquals(2.5, o.rawSeconds / s.rawSeconds, 1e-6)
        assertEquals(3.0, o.rawSeconds / f.rawSeconds, 1e-6)
    }

    @Test fun emptyAndZeroLength() {
        val e = DrawTimeline.build(listOf(layer(1, 1f)))
        assertEquals(0f, e.allowances(0.5f)[1]!!, 0f)
        val z = DrawTimeline.build(emptyList())
        assertTrue(z.allowances(0.5f).isEmpty())
        val d = DrawTimeline.build(listOf(layer(1, 1f, 0f, 0f)))
        assertEquals(0f, d.allowances(0.5f)[1]!!, 0f)
    }

    @Test fun longJumpPausesLongerButCapped() {
        fun gap(dist: Float): Double {
            val l = DrawTimeline.LayerSpec(1, floatArrayOf(10f, 10f), floatArrayOf(0f, dist), floatArrayOf(0f, 0f),
                floatArrayOf(0f, 0f), floatArrayOf(0f, 0f), floatArrayOf(1f, 1f))
            val t = DrawTimeline.build(listOf(l))
            return (t.startOf(1) - t.endOf(0)) * t.rawSeconds
        }
        assertTrue(gap(300f) > gap(10f))
        assertEquals(DrawTimeline.LIFT_MAX_SEC, gap(1_000_000f), 1e-6)
    }
}
