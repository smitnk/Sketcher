package com.smitnk.sketcher.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeGeneratorTest {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    /** 200x200 white image with a black 80x80 square in the middle. */
    private fun squareImage(size: Int = 200, lo: Int = 60, hi: Int = 140): IntArray {
        val px = IntArray(size * size) { white }
        for (y in lo until hi) for (x in lo until hi) px[y * size + x] = black
        return px
    }

    @Test
    fun squareProducesOutlineStrokes() {
        val result = StrokeGenerator.generateBlocking(
            squareImage(), 200, 200,
            GeneratorSettings(includeShading = false),
        )
        assertEquals(200, result.width)
        assertEquals(200, result.height)
        assertTrue("expected outline strokes", result.outline.isNotEmpty())
        assertTrue("shading must be empty when disabled", result.shading.isEmpty())

        // All points must lie within the image, near the square border (allow blur margin).
        for (s in result.outline) {
            var i = 0
            while (i < s.points.size) {
                val x = s.points[i]
                val y = s.points[i + 1]
                assertTrue(x in 50f..150f && y in 50f..150f)
                i += 2
            }
        }
        // The square perimeter is ~320 px; simplification must leave far fewer points.
        val totalPoints = result.outline.sumOf { it.points.size / 2 }
        assertTrue("points after RDP: $totalPoints", totalPoints in 3..100)
    }

    @Test
    fun rdpReducesCollinearPoints() {
        val pts = FloatArray(202)
        for (i in 0..100) {
            pts[i * 2] = i.toFloat()
            pts[i * 2 + 1] = 5f + (if (i % 2 == 0) 0.2f else -0.2f)
        }
        val out = StrokeGenerator.rdp(pts, 1.2f)
        assertEquals(2, out.size / 2)
        assertEquals(0f, out[0], 0f)
        assertEquals(100f, out[out.size - 2], 0f)
    }

    @Test
    fun shadingProducedForDarkRegion() {
        val result = StrokeGenerator.generateBlocking(
            squareImage(), 200, 200,
            GeneratorSettings(includeShading = true),
        )
        assertTrue("expected hatch strokes", result.shading.isNotEmpty())
    }

    @Test
    fun largeImageIsDownscaled() {
        val w = 2048
        val h = 1024
        val px = IntArray(w * h) { white }
        val result = StrokeGenerator.generateBlocking(px, w, h, GeneratorSettings(includeShading = false))
        assertEquals(1024, result.width)
        assertEquals(512, result.height)
        assertTrue(result.outline.isEmpty())
    }
}
