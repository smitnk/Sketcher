package com.smitnk.sketcher.image.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.sqrt

class CartoonLineExtractorTest {

    private val w = 400
    private val h = 300
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /** Flat sky/grass cartoon with a blue disc outlined in 4 px black, plus dark-green texture specks. */
    private fun cartoon(): IntArray {
        val px = IntArray(w * h)
        val rnd = Random(1)
        for (y in 0 until h) for (x in 0 until w) px[y * w + x] = if (y < 150) rgb(120, 190, 245) else rgb(70, 170, 60)
        for (i in 0 until 400) {
            val x = rnd.nextInt(w - 6); val y = 160 + rnd.nextInt(h - 170)
            for (k in 0 until 5) px[y * w + x + k] = rgb(30, 100, 40)
        }
        for (y in 0 until h) for (x in 0 until w) {
            val d = sqrt(((x - 200) * (x - 200) + (y - 150) * (y - 150)).toDouble())
            if (d < 60) px[y * w + x] = rgb(60, 110, 210)
            if (d in 58.0..62.0) px[y * w + x] = rgb(15, 15, 25)
        }
        return px
    }

    /** Smooth noisy gradient "photo". */
    private fun photo(): IntArray {
        val rnd = Random(7)
        return IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            rgb(
                ((128 + 100 * Math.sin(x / 23.0 + y / 41.0)).toInt() + rnd.nextInt(24)).coerceIn(0, 255),
                ((128 + 100 * Math.sin(x / 31.0 - y / 17.0)).toInt() + rnd.nextInt(24)).coerceIn(0, 255),
                ((128 + 100 * Math.cos(y / 29.0 + x / 53.0)).toInt() + rnd.nextInt(24)).coerceIn(0, 255),
            )
        }
    }

    @Test fun cartoonScoresHigh() {
        assertTrue(CartoonLineExtractor.cartoonScore(cartoon(), w, h) > 0.6f)
    }

    @Test fun photoScoresLow() {
        assertTrue(CartoonLineExtractor.cartoonScore(photo(), w, h) < 0.4f)
    }

    @Test fun inkFollowsOutlineAndIgnoresTexture() {
        val ink = CartoonLineExtractor.inkMap(cartoon(), w, h, 0.5f)
        assertEquals(w * h, ink.size)
        assertTrue(ink.all { it in 0f..1f })
        // on the ring
        assertTrue(ink[150 * w + 140] > 0.5f)
        assertTrue(ink[90 * w + 200] > 0.5f)
        // grass specks (y>=160, outside the disc) are suppressed
        var specks = 0
        for (y in 215 until h) for (x in 0 until w) if (ink[y * w + x] > 0.3f) specks++
        assertEquals(0, specks)
        // flat sky / disc interior is paper
        assertEquals(0f, ink[20 * w + 20], 0f)
        assertEquals(0f, ink[150 * w + 200], 0f)
    }

    @Test fun regionBoundaryAppearsOnlyWithHighDetail() {
        // two big flat regions, no outline
        val px = IntArray(w * h) { if (it % w < 200) rgb(240, 240, 60) else rgb(30, 60, 200) }
        val low = CartoonLineExtractor.inkMap(px, w, h, 0.1f)
        val high = CartoonLineExtractor.inkMap(px, w, h, 1f)
        assertEquals(0f, low.max(), 0f)
        assertTrue(high[150 * w + 200] > 0.3f || high[150 * w + 199] > 0.3f)
    }

    @Test fun tinyImagesDoNotCrash() {
        assertEquals(0f, CartoonLineExtractor.cartoonScore(IntArray(4), 2, 2), 0f)
        assertEquals(4, CartoonLineExtractor.inkMap(IntArray(4), 2, 2, 0.5f).size)
    }

    /** Eye: white disc, black ring, solid pupil (too wide for the line detector) on a coloured face. */
    private fun eyeCartoon(outline: Int, noise: Int): IntArray {
        val rnd = Random(3)
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val g = 190 + y * 40 / h
            px[y * w + x] = rgb(g - 120, g - 40, 255)
            val d = sqrt(((x - 200) * (x - 200) + (y - 150) * (y - 150)).toDouble())
            if (d < 70) px[y * w + x] = rgb(250, 250, 250)
            if (d in 68.0..73.0) px[y * w + x] = outline
            if (d < 10) px[y * w + x] = rgb(10, 10, 15)
            if (noise > 0) {
                val c = px[y * w + x]; val e = rnd.nextInt(2 * noise + 1) - noise
                px[y * w + x] = rgb(((c shr 16 and 255) + e).coerceIn(0, 255), ((c shr 8 and 255) + e).coerceIn(0, 255), ((c and 255) + e).coerceIn(0, 255))
            }
        }
        return px
    }

    @Test fun pupilKeptAsSolidDot() {
        val ink = CartoonLineExtractor.inkMap(eyeCartoon(rgb(15, 15, 25), 0), w, h, 0.5f)
        assertTrue(ink[150 * w + 200] > 0.5f)
        assertTrue(ink[150 * w + 191] > 0.5f)
        assertEquals(0f, ink[150 * w + 150], 0f) // eye white stays paper
    }

    @Test fun darkBlueNoisyOutlineIsInk() {
        val ink = CartoonLineExtractor.inkMap(eyeCartoon(rgb(30, 40, 110), 6), w, h, 0.5f)
        var onRing = 0
        for (x in 125 until 135) for (y in 145 until 155) if (ink[y * w + x] > 0.5f) onRing++
        assertTrue(onRing > 0)
        assertTrue(ink[150 * w + 200] > 0.5f)
    }

    @Test
    fun speckleBlobsOnGrassDroppedButEnclosedPupilKept() {
        val px = cartoon()
        // 3x3-ish dark round speckles scattered on grass (cluster), and a pupil inside the disc
        val rnd = Random(5)
        fun blob(cx: Int, cy: Int, r: Int) {
            for (y in cy - r..cy + r) for (x in cx - r..cx + r) if ((x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r) px[y * w + x] = rgb(20, 25, 20)
        }
        val specks = ArrayList<IntArray>()
        for (i in 0 until 20) { val x = 20 + rnd.nextInt(w - 40); val y = 230 + rnd.nextInt(55); blob(x, y, 2); specks.add(intArrayOf(x, y)) }
        blob(190, 140, 7)
        val ink = CartoonLineExtractor.inkMap(px, w, h, 0.5f)
        assertTrue(ink[140 * w + 190] > 0.5f)
        val kept = specks.count { (x, y) -> ink[y * w + x] > 0.5f }
        assertTrue("kept $kept speckles", kept <= 2)
    }
}
