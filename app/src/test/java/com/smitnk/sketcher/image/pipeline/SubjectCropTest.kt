package com.smitnk.sketcher.image.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.sqrt

class SubjectCropTest {
    private val w = 400
    private val h = 600
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /** Soft-vignette grey backdrop with a shaded (gradient) ellipse "figure" in the middle-left. */
    private fun render(): IntArray = IntArray(w * h) { i ->
        val x = i % w; val y = i / w
        val dx = (x - 160) / 60.0; val dy = (y - 300) / 200.0
        val e = dx * dx + dy * dy
        if (e < 1.0) {
            val s = (90 + 120 * (1 - sqrt(e))).toInt()
            rgb(s, 60 + s / 3, 200 - s / 2)
        } else {
            val v = 205 - (sqrt(((x - 200) * (x - 200) + (y - 300) * (y - 300)).toDouble()) / 40).toInt()
            rgb(v, v + 2, v + 8)
        }
    }

    @Test fun findsSubjectOnGradientBackdrop() {
        val r = SubjectCrop.detect(render(), w, h)
        assertTrue(r.hasBackground)
        assertTrue(r.x0 in 0..100 && r.x1 in 220..300)
        assertTrue(r.y0 in 0..120 && r.y1 in 480..h)
        val m = r.bgMask!!
        assertTrue(m[5 * w + 5])
        assertTrue(m[300 * w + 350])
        assertFalse(m[300 * w + 160])          // subject centre
        assertFalse(m[300 * w + 100 - 2])      // just outside the subject edge is kept (outline survives)
    }

    @Test fun letterboxBarsWithStatusGlyphsAreStripped() {
        val px = IntArray(w * h) { rgb(0, 0, 0) }
        val inner = render()
        val top = 100
        for (y in 0 until 400) for (x in 0 until w) px[(top + y) * w + x] = inner[(y + 100) * w + x]
        // status-bar clock and round buttons inside the black bar
        val rnd = Random(3)
        for (i in 0 until 600) px[(10 + rnd.nextInt(10)) * w + 20 + rnd.nextInt(60)] = rgb(220, 220, 220)
        for (y in 40..80) for (x in 20..60) if ((x - 40) * (x - 40) + (y - 60) * (y - 60) < 400) px[y * w + x] = rgb(60, 60, 60)
        val lb = SubjectCrop.letterbox(px, w, h)
        assertNotNull(lb)
        assertEquals(top, lb!![1]); assertEquals(top + 400, lb[3])
        val r = SubjectCrop.detect(px, w, h)
        assertTrue(r.y0 >= top && r.y1 <= top + 400)
        assertTrue(r.bgMask!![30 * w + 40])      // button is background now
    }

    @Test fun noisyPhotoReturnsFullFrame() {
        val rnd = Random(5)
        val px = IntArray(w * h) { rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)) }
        val r = SubjectCrop.detect(px, w, h)
        assertTrue(r.isFull)
        assertFalse(r.hasBackground)
    }
}
