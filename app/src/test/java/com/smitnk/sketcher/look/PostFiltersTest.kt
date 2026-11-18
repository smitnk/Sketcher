package com.smitnk.sketcher.look

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PostFiltersTest {
    private fun ramp(w: Int, h: Int) = IntArray(w * h) { i ->
        val v = ((i % w) * 255) / (w - 1)
        (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }

    private fun twoLevels(px: IntArray) = px.all { it == 0xFF000000.toInt() || it == 0xFFFFFFFF.toInt() }

    @Test fun atkinsonOutputsOnlyTwoLevels() {
        val px = ramp(64, 32)
        PostFilters.atkinson(px, 64, 32)
        assertTrue(twoLevels(px))
        val white = px.count { it == 0xFFFFFFFF.toInt() }
        assertTrue(white in 1 until px.size)
    }

    @Test fun atkinsonKeepsMeanBrightnessRoughly() {
        val px = IntArray(100 * 100) { 0xFF808080.toInt() }
        PostFilters.atkinson(px, 100, 100)
        val white = px.count { it == 0xFFFFFFFF.toInt() } / px.size.toDouble()
        assertEquals(0.5, white, 0.1)
    }

    @Test fun bayerMapIsPermutationOf0to63() {
        assertEquals((0..63).toList(), PostFilters.BAYER8.sorted())
        assertEquals(0, PostFilters.BAYER8[0])
        assertEquals(32, PostFilters.BAYER8[1])
        assertEquals(48, PostFilters.BAYER8[8])
    }

    @Test fun bayerThresholdsAreTiledAndInRange() {
        for (y in 0 until 16) for (x in 0 until 16) {
            val t = PostFilters.bayerThreshold(x, y)
            assertTrue(t > 0f && t < 1f)
            assertEquals(t, PostFilters.bayerThreshold(x + 8, y + 8), 0f)
        }
    }

    @Test fun bayerExtremesAndTwoLevels() {
        val black = IntArray(64) { 0xFF000000.toInt() }
        val white = IntArray(64) { 0xFFFFFFFF.toInt() }
        PostFilters.bayerDither(black, 8, 8)
        PostFilters.bayerDither(white, 8, 8)
        assertTrue(black.all { it == 0xFF000000.toInt() })
        assertTrue(white.all { it == 0xFFFFFFFF.toInt() })
        val px = ramp(64, 8)
        PostFilters.bayerDither(px, 64, 8)
        assertTrue(twoLevels(px))
    }

    @Test fun halftoneIsDeterministicAndTwoLevel() {
        val a = ramp(120, 80)
        val b = ramp(120, 80)
        PostFilters.halftone(a, 120, 80, 1)
        PostFilters.halftone(b, 120, 80, 1)
        assertArrayEquals(a, b)
        assertTrue(twoLevels(a))
        assertTrue(a.any { it == 0xFF000000.toInt() } && a.any { it == 0xFFFFFFFF.toInt() })
    }

    @Test fun halftoneDarkerMeansMoreInk() {
        fun ink(g: Int): Int {
            val px = IntArray(100 * 100) { (0xFF shl 24) or (g shl 16) or (g shl 8) or g }
            PostFilters.halftone(px, 100, 100)
            return px.count { it == 0xFF000000.toInt() }
        }
        assertTrue(ink(40) > ink(128))
        assertTrue(ink(128) > ink(220))
    }

    @Test fun transparentInkKeepsOnlyInk() {
        val px = IntArray(64) { if (it < 16) 0xFF000000.toInt() else 0 }
        PostFilters.applyToInk(px, 8, 8, PostFilter.ONE_BIT_BAYER)
        assertTrue(px.all { it == 0xFF000000.toInt() || it == 0 })
        assertTrue(px.take(16).all { it == 0xFF000000.toInt() })
        assertTrue(px.drop(16).all { it == 0 })
    }

    @Test fun paperIsSeededDeterministicOpaqueAndMisregisterInRange() {
        val a = PaperTexture.generate(Paper.PENCIL_TOOTH, 64, 48, 5)
        val b = PaperTexture.generate(Paper.PENCIL_TOOTH, 64, 48, 5)
        val c = PaperTexture.generate(Paper.PENCIL_TOOTH, 64, 48, 6)
        assertArrayEquals(a, b)
        assertTrue(!a.contentEquals(c))
        assertTrue(a.all { (it ushr 24) == 0xFF })
        for (seed in 0 until 50) {
            val (dx, dy) = Misregister.offset(seed)
            val m = Math.hypot(dx.toDouble(), dy.toDouble())
            assertTrue(m in 1.49..2.51)
        }
    }
}
