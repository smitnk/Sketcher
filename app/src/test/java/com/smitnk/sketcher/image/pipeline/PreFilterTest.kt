package com.smitnk.sketcher.image.pipeline

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PreFilterTest {
    private fun px(g: Int) = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
    private fun lum(p: Int) = p and 255

    @Test fun zeroStrengthReturnsInput() {
        val a = IntArray(32 * 32) { px(it % 200) }
        assertEquals(a.toList(), PreFilter.smoothTextures(a, 32, 32, 0f).toList())
    }

    @Test fun noiseTextureIsSmoothedButStepEdgeKept() {
        val w = 160; val h = 120; val rnd = Random(1)
        // left half: heavy noise texture; right half: flat; a bright block with a hard edge inside the flat part
        val a = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            when {
                x < 80 -> px(if (rnd.nextBoolean()) 70 else 190)
                x in 110..140 && y in 40..80 -> px(240)
                else -> px(60)
            }
        }
        val o = PreFilter.smoothTextures(a, w, h, 0.7f)
        fun variance(x0: Int, x1: Int): Double {
            var s = 0.0; var s2 = 0.0; var n = 0
            for (y in 30 until 90) for (x in x0 until x1) { val v = lum(o[y * w + x]).toDouble(); s += v; s2 += v * v; n++ }
            return s2 / n - (s / n) * (s / n)
        }
        assertTrue(variance(20, 60) < 1500.0, "texture variance should fall from ~3600")
        assertTrue(lum(o[60 * w + 125]) > 220 && lum(o[60 * w + 100]) < 80, "block edge preserved")
    }

    @Test fun fastEnough() {
        val w = 640; val h = 480; val rnd = Random(2)
        val a = IntArray(w * h) { px(rnd.nextInt(256)) }
        PreFilter.smoothTextures(a, w, h, 0.6f)
        val t = System.currentTimeMillis()
        PreFilter.smoothTextures(a, w, h, 0.6f)
        assertTrue(System.currentTimeMillis() - t < 1000)
    }
}
