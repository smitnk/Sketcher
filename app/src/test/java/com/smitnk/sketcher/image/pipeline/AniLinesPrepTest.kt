package com.smitnk.sketcher.image.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AniLinesPrepTest {
    @Test fun fitSizeKeepsAspectAndMultipleOf16AfterPadding() {
        val (rw, rh) = AniLinesPrep.fitSize(4000, 3000, 768, 768 * 768)
        assertEquals(768, rw); assertEquals(576, rh)
        assertEquals(576, AniLinesPrep.padded(rh))
        assertEquals(48, AniLinesPrep.padded(41))
        val (a, b) = AniLinesPrep.fitSize(10, 10, 768, 768 * 768)
        assertTrue(a >= 16 && b >= 16)
    }

    @Test fun reflectMirrorsWithoutRepeatingEdge() {
        assertEquals(1, AniLinesPrep.reflect(-1, 5)); assertEquals(3, AniLinesPrep.reflect(5, 5))
        assertEquals(2, AniLinesPrep.reflect(6, 5)); assertEquals(0, AniLinesPrep.reflect(0, 1))
    }

    @Test fun planesPadReflectAndCropRoundTrip() {
        val w = 20; val h = 18
        val px = IntArray(w * h) { if (it % w == 10) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        val pw = AniLinesPrep.padded(w); val ph = AniLinesPrep.padded(h)
        assertEquals(32, pw); assertEquals(32, ph)
        val p = AniLinesPrep.planes(px, w, h, w, h)
        assertEquals(2 * pw * ph, p.size)
        val gray = AniLinesPrep.crop(p, pw, w, h)
        assertEquals(w * h, gray.size)
        assertEquals(0f, gray[10], 1e-3f); assertEquals(1f, gray[3], 1e-3f)
        assertEquals(p[w - 2], p[w], 1e-6f) // padded column mirrors the one before the edge
    }

    @Test fun sobelChannelIsDarkAtEdgesAndOneWhenFlat() {
        val w = 16; val h = 16
        val g = FloatArray(w * h) { if (it % w < 8) 0f else 1f }
        val s = AniLinesPrep.sobelInverted(g, w, h)
        assertEquals(0f, s[5 * w + 7], 1e-5f) // strongest edge = 0 (ink-like)
        assertEquals(1f, s[5 * w + 2], 1e-5f)
        assertTrue(AniLinesPrep.sobelInverted(FloatArray(w * h), w, h).all { it == 1f })
    }
}
