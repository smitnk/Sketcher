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

    private fun gen(px: IntArray, size: Int, settings: GeneratorSettings) =
        StrokeGenerator.generateBlocking(px, size, size, settings)

    /** Thick dark ring (6 px wide) on white. */
    private fun ringImage(size: Int = 200, radius: Int = 70, thickness: Int = 6): IntArray {
        val px = IntArray(size * size) { white }
        val c = size / 2f
        for (y in 0 until size) for (x in 0 until size) {
            val d = Math.hypot((x - c).toDouble(), (y - c).toDouble())
            if (Math.abs(d - radius) <= thickness / 2.0) px[y * size + x] = black
        }
        return px
    }

    @Test
    fun thickLineGivesSingleCentreLine() {
        val r = gen(ringImage(), 200, GeneratorSettings(includeShading = false))
        assertTrue("expected a stroke", r.outline.isNotEmpty())
        // every point sits on the ring centre line (radius 70), not on its two edges (67 / 73)
        for (s in r.outline) {
            var i = 0
            while (i < s.points.size) {
                val d = Math.hypot((s.points[i] - 100.0), (s.points[i + 1] - 100.0))
                assertTrue("point off centre line: $d", Math.abs(d - 70.0) < 2.0)
                i += 2
            }
        }
        // one closed ring, not many fragments
        assertTrue("fragments: ${r.outline.size}", r.outline.size <= 2)
    }

    @Test
    fun noiseProducesNoOutline() {
        val rnd = java.util.Random(7)
        val px = IntArray(200 * 200) {
            val v = (128 + rnd.nextGaussian() * 6).toInt().coerceIn(0, 255)
            0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
        }
        val r = gen(px, 200, GeneratorSettings(includeShading = false))
        assertTrue("speckles must be ignored, got ${r.outline.size}", r.outline.size <= 2)
    }

    @Test
    fun smallGapIsBridged() {
        val px = IntArray(200 * 200) { white }
        // horizontal line with a 6 px hole in the middle
        for (x in 20 until 180) if (x !in 97..103) for (y in 99..100) px[y * 200 + x] = black
        val r = gen(px, 200, GeneratorSettings(includeShading = false))
        assertEquals("gap should be closed into one stroke", 1, r.outline.size)
        assertTrue(r.outline[0].length > 150f)
    }

    @Test
    fun circleIsSmooth() {
        val r = gen(ringImage(thickness = 2), 200, GeneratorSettings(includeShading = false))
        val s = r.outline.maxByOrNull { it.length }!!
        assertTrue("ring should be one long stroke", s.length > 400f)
        // curve vertices are dense enough that no segment cuts the circle by more than 1 px
        var i = 2
        while (i < s.points.size) {
            val mx = (s.points[i] + s.points[i - 2]) / 2.0 - 100.0
            val my = (s.points[i + 1] + s.points[i - 1]) / 2.0 - 100.0
            assertTrue(Math.abs(Math.hypot(mx, my) - 70.0) < 2.5)
            i += 2
        }
    }

    @Test
    fun longStrokesComeFirst() {
        val px = IntArray(200 * 200) { white }
        for (x in 10 until 190) px[20 * 200 + x] = black
        for (x in 10 until 190) px[21 * 200 + x] = black
        for (x in 80 until 100) for (y in 150..151) px[y * 200 + x] = black
        val r = gen(px, 200, GeneratorSettings(includeShading = false, minStrokeLength = 8))
        assertTrue(r.outline.size >= 2)
        assertTrue(r.outline.first().length >= r.outline.last().length)
    }

    @Test
    fun detailSettingChangesOutline() {
        // soft low-contrast ring plus a strong square: only high detail keeps the faint ring
        val size = 200
        val px = IntArray(size * size) { white }
        for (y in 20 until 70) for (x in 20 until 70) px[y * size + x] = black
        for (y in 0 until size) for (x in 0 until size) {
            val d = Math.hypot(x - 130.0, y - 130.0)
            if (Math.abs(d - 40.0) <= 3.0) px[y * size + x] = 0xFFD8D8D8.toInt()
        }
        val low = gen(px, size, GeneratorSettings(detail = 0.1f, includeShading = false))
        val high = gen(px, size, GeneratorSettings(detail = 0.95f, includeShading = false))
        val lowLen = low.outline.sumOf { it.length.toDouble() }
        val highLen = high.outline.sumOf { it.length.toDouble() }
        assertTrue("detail should add ink: $lowLen vs $highLen", highLen > lowLen * 1.4)
    }

    @Test
    fun hatchLinesAreNotTinyAndStayInsideDarkRegion() {
        val r = gen(squareImage(), 200, GeneratorSettings(includeShading = true))
        assertTrue(r.shading.isNotEmpty())
        for (s in r.shading) {
            assertTrue("tiny dash: ${s.length}", s.length >= 8f)
            var i = 0
            while (i < s.points.size) {
                assertTrue(s.points[i] in 55f..145f && s.points[i + 1] in 55f..145f)
                i += 2
            }
        }
    }
}

