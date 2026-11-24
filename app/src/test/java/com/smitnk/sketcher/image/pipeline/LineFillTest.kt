package com.smitnk.sketcher.image.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.sqrt

class LineFillTest {
    /** Circle outline (ring 3 px thick) centred in a size x size image, with an opening of [gapPx] px on the right. */
    private fun circle(size: Int, gapPx: Int): BooleanArray {
        val c = size / 2.0; val r = size * 0.3
        return BooleanArray(size * size) { i ->
            val x = i % size - c; val y = i / size - c
            val d = sqrt(x * x + y * y)
            d in (r - 1.5)..(r + 1.5) && !(x > 0 && y >= -gapPx / 2 && y < gapPx / 2)
        }
    }

    @Test fun gapOfTwoPixelsDoesNotLeak() {
        val s = 200
        for (gap in GapClosing.values()) {
            val m = LineFill.regions(circle(s, 2), s, s, gap)
            assertNotEquals("$gap: inside vs outside", m.labels[s / 2 * s + s / 2], m.labels[5 * s + 5])
        }
    }

    @Test fun regionCountStableAcrossRunsAndGaps() {
        val s = 160
        val ink = circle(s, 0)
        val counts = GapClosing.values().map { LineFill.regions(ink, s, s, it).count }
        assertEquals(listOf(2, 2, 2), counts)
        assertEquals(2, LineFill.regions(ink, s, s).count)
    }

    @Test fun everyPixelLabelledAndColoursAreMedians() {
        val s = 100
        val m = LineFill.regions(circle(s, 0), s, s)
        assertTrue(m.labels.all { it in 0 until m.count })
        val red = 0xFFFF0000.toInt(); val blue = 0xFF0000FF.toInt()
        val src = IntArray(s * s) { i -> val x = i % s - 50.0; val y = i / s - 50.0; if (x * x + y * y < 900) red else blue }
        val cols = LineFill.flatColours(m, src)
        assertEquals(red, cols[m.labels[50 * s + 50]])
        assertEquals(blue, cols[m.labels[0]])
        assertEquals(1, LineFill.flatColours(m, src, paletteSize = 1).toSet().size)
    }

    @Test fun largeLineArtIsFast() {
        val s = 1024
        // grid of circles with gaps + a few straight lines
        val ink = BooleanArray(s * s) { i ->
            val x = i % s; val y = i / s
            val cx = (x % 128) - 64.0; val cy = (y % 128) - 64.0
            val d = sqrt(cx * cx + cy * cy)
            (d in 40.0..42.0 && atan2(cy, cx) !in 0.0..0.05) || x % 256 == 0 || y == 700
        }
        LineFill.regions(ink, 256, 256) // warm-up on a slice
        val t = System.nanoTime()
        val m = LineFill.regions(ink, s, s)
        val ms = (System.nanoTime() - t) / 1_000_000
        assertTrue("took $ms ms", ms < 1500)
        assertTrue(m.count > 64)
        assertTrue(LineFill.celStrokes(m, LineFill.flatColours(m, IntArray(s * s) { 0xFF808080.toInt() }), 6f).isNotEmpty())
    }
}
