package com.smitnk.sketcher.anim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class ArrivalMapTest {
    private fun len(p: FloatArray): Float {
        var t = 0f; var i = 2
        while (i < p.size) { val dx = p[i] - p[i - 2]; val dy = p[i + 1] - p[i - 1]; t += sqrt(dx * dx + dy * dy); i += 2 }
        return t
    }

    @Test fun monotonicAlongStroke() {
        val p = floatArrayOf(5f, 20f, 40f, 20f, 40f, 40f, 90f, 40f)
        val m = ArrivalBaker.bake(100, 60, 1f, listOf(p), floatArrayOf(len(p)), 3f)
        var prev = -1f
        for (x in 5..40) { val a = m.data[20 * 100 + x]; assertTrue(a >= prev); prev = a }
        assertEquals(len(p), m.total, 1e-3f)
        assertEquals(35f, m.data[20 * 100 + 40], 1.5f)
        assertEquals(len(p), m.data[40 * 100 + 89], 1.5f)
    }

    @Test fun earliestWins() {
        val a = floatArrayOf(10f, 30f, 90f, 30f)   // length 80, crosses b at x=50
        val b = floatArrayOf(50f, 5f, 50f, 55f)    // starts after a: base 80
        val m = ArrivalBaker.bake(100, 60, 1f, listOf(a, b), floatArrayOf(80f, 50f), 2f)
        assertEquals(40f, m.data[30 * 100 + 50], 1.5f)       // from a, not b (80+25)
        assertEquals(80f + 10f, m.data[15 * 100 + 50], 1.5f) // b only
    }

    @Test fun coverageEqualsUnion() {
        val strokes = listOf(floatArrayOf(10f, 10f, 80f, 50f, 20f, 55f), floatArrayOf(5f, 60f, 95f, 5f))
        val r = 4f
        val m = ArrivalBaker.bake(100, 64, 1f, strokes, floatArrayOf(len(strokes[0]), len(strokes[1])), r)
        fun dist(px: Float, py: Float, p: FloatArray): Float {
            var best = Float.MAX_VALUE; var i = 2
            while (i < p.size) {
                val ax = p[i - 2]; val ay = p[i - 1]; val dx = p[i] - ax; val dy = p[i + 1] - ay
                val t = (((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)).coerceIn(0f, 1f)
                val ex = px - ax - dx * t; val ey = py - ay - dy * t
                best = minOf(best, sqrt(ex * ex + ey * ey)); i += 2
            }
            return best
        }
        for (y in 0 until 64) for (x in 0 until 100) {
            val d = strokes.minOf { dist(x + .5f, y + .5f, it) }
            val reached = m.data[y * 100 + x] != ArrivalMap.NEVER
            if (d < r - 0.01f) assertTrue("($x,$y) should be reached", reached)
            if (d > r + 0.01f) assertTrue("($x,$y) should be unreached", !reached)
        }
    }

    @Test fun bakeSpeed() {
        val rnd = java.util.Random(1)
        val strokes = List(1000) { val x = rnd.nextFloat() * 900f; val y = rnd.nextFloat() * 900f
            FloatArray(40) { k -> if (k % 2 == 0) x + k * 2f else y + rnd.nextFloat() * 20f } }
        val lens = FloatArray(1000) { len(strokes[it]) }
        ArrivalBaker.bake(1024, 1024, 1f, strokes, lens, 3f)
        val t0 = System.nanoTime()
        ArrivalBaker.bake(1024, 1024, 1f, strokes, lens, 3f)
        println("ARRIVAL_BAKE_MS=" + (System.nanoTime() - t0) / 1_000_000)
    }
}
