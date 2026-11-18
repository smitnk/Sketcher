package com.smitnk.sketcher.look

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class StrokeFeelGeometryTest {
    private fun line(n: Int = 60, len: Float = 300f): FloatArray {
        val p = FloatArray(n * 2)
        for (i in 0 until n) { p[2 * i] = 10f + len * i / (n - 1); p[2 * i + 1] = 50f + 10f * sin(i * 0.2f) }
        return p
    }
    private fun circle(): FloatArray {
        val n = 80
        return FloatArray(n * 2).also { for (i in 0 until n) { val a = 2 * PI * i / (n - 1); it[2 * i] = (100 + 60 * cos(a)).toFloat(); it[2 * i + 1] = (100 + 60 * sin(a)).toFloat() } }
    }

    @Test fun resampleKeepsEndsAndSpacing() {
        val p = line()
        val r = StrokeFeelGeometry.resample(p, 5f)
        assertEquals(p[0], r[0], 0f); assertEquals(p[p.size - 2], r[r.size - 2], 0f)
        assertEquals(StrokeFeelGeometry.polyLength(p), StrokeFeelGeometry.polyLength(r), StrokeFeelGeometry.polyLength(p) * 0.02f)
        var i = 2
        while (i < r.size - 2) { assertEquals(5.0, hypot((r[i] - r[i - 2]).toDouble(), (r[i + 1] - r[i - 1]).toDouble()), 0.6); i += 2 }
    }

    @Test fun wobbleBoundedAnchoredAndDeterministic() {
        val r = StrokeFeelGeometry.resample(line(), 4f)
        val a = StrokeFeelGeometry.wobble(r, 2.6f, 220f, 5)
        val b = StrokeFeelGeometry.wobble(r, 2.6f, 220f, 5)
        assertArrayEquals(a, b, 0f)
        assertEquals(r[0], a[0], 0f); assertEquals(r[r.size - 1], a[a.size - 1], 0f)
        for (i in 0 until r.size / 2) assertTrue(hypot((a[2 * i] - r[2 * i]).toDouble(), (a[2 * i + 1] - r[2 * i + 1]).toDouble()) <= 2.6 * 1.5 + 1e-3)
        assertTrue(a.indices.any { a[it] != r[it] })
    }

    @Test fun closureOvershootExtendsPastEnd() {
        val c = circle()
        assertTrue(StrokeFeelGeometry.isClosed(c, 6f))
        assertTrue(!StrokeFeelGeometry.isClosed(line(), 6f))
        val o = StrokeFeelGeometry.closureOvershoot(c, 8f, 3)
        assertEquals(c.size + 8, o.size)
        assertEquals(8.0, hypot((o[o.size - 2] - c[c.size - 2]).toDouble(), (o[o.size - 1] - c[c.size - 1]).toDouble()), 0.5)
    }

    @Test fun pressureRampsInAndTapersOut() {
        val h = StrokeFeelGeometry.pressureHalfWidths(StrokeFeelGeometry.resample(line(len = 400f), 4f), 9f)
        assertTrue(h[0] < h[h.size / 2])
        assertTrue(h[h.size - 1] < h[h.size / 2])
        assertTrue(h.all { it > 0f })
    }

    @Test fun ribbonRevealMonotonicAndClosed() {
        val p = StrokeFeelGeometry.resample(line(), 4f)
        val half = StrokeFeelGeometry.pressureHalfWidths(p, 8f)
        val full = StrokeFeelGeometry.polyLength(p)
        assertEquals(0, StrokeFeelGeometry.ribbonOutline(p, half, 0f).size)
        var prevMaxX = -1f
        for (k in 1..20) {
            val o = StrokeFeelGeometry.ribbonOutline(p, half, full * k / 20f)
            assertTrue(o.size >= 6 && o.size % 2 == 0)
            var mx = -1e9f; var i = 0; while (i < o.size) { mx = maxOf(mx, o[i]); i += 2 }
            assertTrue(mx >= prevMaxX - 1e-3f); prevMaxX = mx
        }
        assertEquals(StrokeFeelGeometry.ribbonOutline(p, half, full).size, StrokeFeelGeometry.ribbonOutline(p, half, full * 5).size)
    }

    @Test fun cleanIsNullAndShapesDeterministic() {
        val p = line()
        assertNull(StrokeFeelGeometry.shapeFor(p, 6f, StrokeFeel.CLEAN, 1, 0))
        for (f in listOf(StrokeFeel.WHITEBOARD_MARKER, StrokeFeel.PEN_PLOTTER, StrokeFeel.SCRATCH_BOIL)) {
            val a = StrokeFeelGeometry.build(p, 6f, f, 9, 3)!!; val b = StrokeFeelGeometry.build(p, 6f, f, 9, 3)!!
            assertEquals(a.lines.size, b.lines.size)
            for (i in a.lines.indices) assertArrayEquals(a.lines[i].pts, b.lines[i].pts, 0f)
        }
    }

    @Test fun plotterHasPooledDotAndIsStraight() {
        val p = line()
        val s = StrokeFeelGeometry.build(p, 6f, StrokeFeel.PEN_PLOTTER, 1, 0)!!
        assertTrue(s.dotR > 0f); assertEquals(p[0], s.dotX, 0f)
        assertArrayEquals(p, s.lines[0].pts, 0f)
    }

    @Test fun boilChangesPerDrawingButNotWithinOne() {
        val p = line()
        val a = StrokeFeelGeometry.build(p, 6f, StrokeFeel.SCRATCH_BOIL, 7, 0)!!
        val b = StrokeFeelGeometry.build(p, 6f, StrokeFeel.SCRATCH_BOIL, 7, 1)!!
        assertTrue(a.lines.size >= 2)
        assertTrue(a.lines[1].pts.size != b.lines[1].pts.size || !a.lines[1].pts.contentEquals(b.lines[1].pts))
        // cache: same drawing -> same object; whiteboard ignores the drawing index
        assertTrue(StrokeFeelGeometry.shapeFor(p, 6f, StrokeFeel.SCRATCH_BOIL, 7, 4) === StrokeFeelGeometry.shapeFor(p, 6f, StrokeFeel.SCRATCH_BOIL, 7, 4))
        assertTrue(StrokeFeelGeometry.shapeFor(p, 6f, StrokeFeel.WHITEBOARD_MARKER, 7, 1) === StrokeFeelGeometry.shapeFor(p, 6f, StrokeFeel.WHITEBOARD_MARKER, 7, 99))
    }

    @Test fun lineLimitMapsFractionToFullLength() {
        val s = StrokeFeelGeometry.build(line(), 6f, StrokeFeel.SCRATCH_BOIL, 2, 0)!!
        for (l in s.lines) {
            assertEquals(l.length, l.limitFor(1f), 1e-3f)
            assertEquals(0f, l.limitFor(0f), 0f)
            assertTrue(l.limitFor(l.from + (l.to - l.from) / 2) in 0f..l.length)
        }
        assertNotNull(StrokeFeelGeometry.build(floatArrayOf(5f, 5f), 4f, StrokeFeel.WHITEBOARD_MARKER, 1, 0))
    }
}
