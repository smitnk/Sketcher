package com.smitnk.sketcher.image.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class LineArtVectorizerTest {
    private val w = 256
    private val h = 256

    private fun plot(ink: FloatArray, x: Int, y: Int, v: Float) {
        for (dy in 0..1) for (dx in 0..1) {
            val xx = x + dx; val yy = y + dy
            if (xx in 0 until w && yy in 0 until h && ink[yy * w + xx] < v) ink[yy * w + xx] = v
        }
    }

    private fun line(ink: FloatArray, x0: Int, y0: Int, x1: Int, y1: Int, v: Float) {
        val n = maxOf(kotlin.math.abs(x1 - x0), kotlin.math.abs(y1 - y0))
        for (i in 0..n) plot(ink, x0 + (x1 - x0) * i / n, y0 + (y1 - y0) * i / n, v)
    }

    private fun circle(ink: FloatArray, cx: Int, cy: Int, r: Int, v: Float) {
        for (i in 0 until 720) {
            val a = i * PI / 360
            plot(ink, (cx + r * cos(a)).toInt(), (cy + r * sin(a)).toInt(), v)
        }
    }

    @Test fun emptyInkGivesNoStrokes() {
        assertTrue(LineArtVectorizer.vectorize(FloatArray(w * h), w, h, 0.5f, 2f, 100).isEmpty())
    }

    @Test fun circleBecomesFewSmoothStrokesNearTheRing() {
        val ink = FloatArray(w * h)
        circle(ink, 128, 128, 60, 0.8f)
        val s = LineArtVectorizer.vectorize(ink, w, h, 0.5f, 2f, 100)
        assertTrue("strokes=${s.size}", s.size in 1..2)
        for (st in s) {
            var i = 0
            while (i < st.points.size) {
                val r = hypot(st.points[i] - 128.5f, st.points[i + 1] - 128.5f)
                assertTrue("r=$r", r in 57f..64f)
                i += 2
            }
        }
        assertTrue(s.sumOf { it.length.toDouble() } > 300.0)
    }

    @Test fun smallGapIsBridgedLargeGapIsNot() {
        val a = FloatArray(w * h)
        line(a, 20, 100, 110, 100, 0.8f); line(a, 116, 100, 230, 100, 0.8f)
        assertEquals(1, LineArtVectorizer.vectorize(a, w, h, 0.5f, 2f, 100).size)
        val b = FloatArray(w * h)
        line(b, 20, 100, 90, 100, 0.8f); line(b, 150, 100, 230, 100, 0.8f)
        assertEquals(2, LineArtVectorizer.vectorize(b, w, h, 0.5f, 2f, 100).size)
    }

    @Test fun crossingLinesRunStraightThrough() {
        val ink = FloatArray(w * h)
        line(ink, 20, 128, 230, 128, 0.8f); line(ink, 128, 20, 128, 230, 0.8f)
        val s = LineArtVectorizer.vectorize(ink, w, h, 0.5f, 2f, 100)
        assertEquals(2, s.size)
        assertTrue(s.all { it.length > 190f })
    }

    @Test fun faintLinesAreDroppedAtLowDetailAndKeptAtHigh() {
        val ink = FloatArray(w * h)
        line(ink, 20, 60, 230, 60, 0.9f)
        line(ink, 20, 180, 230, 180, 0.3f)
        assertEquals(1, LineArtVectorizer.vectorize(ink, w, h, 0.0f, 2f, 100).size)
        assertEquals(2, LineArtVectorizer.vectorize(ink, w, h, 1.0f, 2f, 100).size)
    }

    @Test fun maxStrokesCapKeepsLongest() {
        val ink = FloatArray(w * h)
        for (i in 0 until 10) line(ink, 10, 10 + i * 20, 20 + i * 20, 10 + i * 20, 0.8f)
        val s = LineArtVectorizer.vectorize(ink, w, h, 0.5f, 2f, 3)
        assertEquals(3, s.size)
        assertTrue(s.all { it.length > 140f })
    }

    @Test fun widthStaysInRangeAndStrongerInkIsThicker() {
        val ink = FloatArray(w * h)
        line(ink, 20, 60, 230, 60, 0.95f)
        line(ink, 20, 180, 230, 180, 0.35f)
        val s = LineArtVectorizer.vectorize(ink, w, h, 1.0f, 3f, 100)
        assertEquals(2, s.size)
        assertTrue(s.all { it.width in 3f * 0.6f - 1e-3f..3f * 1.6f + 1e-3f })
        val strong = s.first { it.points[1] < 100f }
        val weak = s.first { it.points[1] > 100f }
        assertTrue(strong.width > weak.width)
    }

    @Test fun longestStrokeIsDrawnFirst() {
        val ink = FloatArray(w * h)
        line(ink, 10, 20, 60, 20, 0.8f)
        line(ink, 10, 200, 240, 200, 0.8f)
        val s = LineArtVectorizer.vectorize(ink, w, h, 0.5f, 2f, 100)
        assertEquals(2, s.size)
        assertTrue(s[0].length > s[1].length)
    }

    @Test fun runsFastAtWorkingSize() {
        val n = 1024
        val ink = FloatArray(n * n)
        val rnd = java.util.Random(1)
        for (k in 0 until 400) {
            var x = rnd.nextInt(n); var y = rnd.nextInt(n)
            for (t in 0 until 60) {
                if (x in 1 until n - 1 && y in 1 until n - 1) { ink[y * n + x] = 0.7f; ink[y * n + x + 1] = 0.7f }
                x += rnd.nextInt(3) - 1 + 1; y += rnd.nextInt(3) - 1
            }
        }
        LineArtVectorizer.vectorize(ink, n, n, 0.5f, 2f, 4000)
        val t0 = System.nanoTime()
        val s = LineArtVectorizer.vectorize(ink, n, n, 0.5f, 2f, 4000)
        val ms = (System.nanoTime() - t0) / 1e6
        assertTrue("ms=$ms", ms < 1000)
        assertTrue(s.isNotEmpty())
    }
}
