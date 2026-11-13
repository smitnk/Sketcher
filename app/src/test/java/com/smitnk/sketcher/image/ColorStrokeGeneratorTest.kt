package com.smitnk.sketcher.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorStrokeGeneratorTest {

    private val white = 0xFFFFFFFF.toInt()
    private val red = 0xFFDD2222.toInt()
    private val blue = 0xFF2244CC.toInt()

    /** 200x200: white background, red square (left) and blue square (right). */
    private fun image(): IntArray {
        val px = IntArray(200 * 200) { white }
        for (y in 50 until 150) for (x in 20 until 90) px[y * 200 + x] = red
        for (y in 50 until 150) for (x in 110 until 180) px[y * 200 + x] = blue
        return px
    }

    private fun r(c: Int) = (c shr 16) and 255
    private fun b(c: Int) = c and 255

    @Test
    fun fillStrokesCoverRegionsInTheirColour() {
        val fill = ColorStrokeGenerator.generateFill(image(), 200, 200, 200, 200, colors = 6, spacing = 5f)
        assertTrue(fill.isNotEmpty())
        val reds = fill.filter { r(it.color) > 150 && b(it.color) < 100 }
        val blues = fill.filter { b(it.color) > 150 && r(it.color) < 100 }
        assertTrue("red strokes", reds.isNotEmpty())
        assertTrue("blue strokes", blues.isNotEmpty())
        for (s in reds) for (i in s.points.indices step 2) assertTrue(s.points[i] in 15f..95f)
        for (s in blues) for (i in s.points.indices step 2) assertTrue(s.points[i] in 105f..185f)
        for (s in fill) {
            assertEquals(6.5f, s.width, 0.01f)
            assertTrue(s.points.size >= 4)
        }
        // white background is skipped
        assertTrue(fill.none { r(it.color) > 240 && b(it.color) > 240 })
    }

    @Test
    fun zigZagIsContinuous() {
        val fill = ColorStrokeGenerator.generateFill(image(), 200, 200, 200, 200, colors = 6, spacing = 5f)
        // 100px tall square with 5px spacing -> ~20 lines; most should be chained into one stroke
        val longest = fill.maxOf { it.points.size / 2 }
        assertTrue("expected chained zig-zag, got $longest points", longest > 20)
    }

    @Test
    fun outlineTakesImageColour() {
        val src = listOf(Stroke(floatArrayOf(30f, 60f, 30f, 140f), 0xFF222222.toInt(), 1.8f))
        val out = ColorStrokeGenerator.colorizeOutline(src, image(), 200, 200, 200, 200)
        assertEquals(1, out.size)
        assertTrue(r(out[0].color) > b(out[0].color))
        assertEquals(1.8f, out[0].width, 0f)
        assertTrue(r(out[0].color) < 0xDD) // darkened
    }

    @Test
    fun gradientBecomesSmoothPerStrokeColours() {
        // vertical blue gradient, dark at top -> light at bottom
        val n = 300
        val px = IntArray(n * n) { i -> val y = i / n; val v = 120 + y * 100 / n; (0xFF shl 24) or ((v - 60) shl 16) or ((v - 20) shl 8) or 255 }
        val fill = ColorStrokeGenerator.generateFill(px, n, n, n, n, colors = 8, spacing = 5f)
        assertTrue(fill.isNotEmpty())
        assertTrue("distinct colours ${fill.map { it.color }.toSet().size}", fill.map { it.color }.toSet().size >= 8)
        val top = fill.filter { it.points[1] < 60f }.map { r(it.color) }
        val bottom = fill.filter { it.points[1] > 240f }.map { r(it.color) }
        assertTrue(top.isNotEmpty() && bottom.isNotEmpty())
        assertTrue(top.average() < bottom.average())
    }

    @Test
    fun fillStaysInsideOutlineAndOffPaper() {
        val px = IntArray(200 * 200) { white }
        for (y in 50 until 150) for (x in 50 until 150) {
            val edge = x < 53 || x >= 147 || y < 53 || y >= 147
            px[y * 200 + x] = if (edge) 0xFF111111.toInt() else red
        }
        val fill = ColorStrokeGenerator.generateFill(px, 200, 200, 200, 200, colors = 6, spacing = 4f)
        assertTrue(fill.isNotEmpty())
        for (s in fill) for (i in s.points.indices step 2) {
            assertTrue("x=${s.points[i]}", s.points[i] in 52f..148f)
            assertTrue("y=${s.points[i + 1]}", s.points[i + 1] in 52f..148f)
        }
    }

    @Test
    fun runsFastOnLargeImage() {
        val n = 1024
        val px = IntArray(n * n) { i -> val x = i % n; val y = i / n; (0xFF shl 24) or ((x / 4 and 255) shl 16) or ((y / 4 and 255) shl 8) or 128 }
        val t0 = System.nanoTime()
        ColorStrokeGenerator.generateFill(px, n, n, n, n, colors = 16, spacing = 4f)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("took $ms ms", ms < 2000)
    }
}
