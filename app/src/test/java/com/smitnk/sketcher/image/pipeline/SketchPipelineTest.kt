package com.smitnk.sketcher.image.pipeline

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class SketchPipelineTest {
    private val w = 600
    private val h = 800
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /** Shaded figure occupying ~1/3 of the width on a flat grey backdrop. */
    private fun scene() = IntArray(w * h) { i ->
        val x = i % w; val y = i / w
        val dx = (x - 300) / 100.0; val dy = (y - 400) / 300.0
        val e = dx * dx + dy * dy
        if (e < 1.0) { val s = (60 + 160 * (1 - sqrt(e))).toInt(); rgb(s, 80 + s / 3, 210 - s / 2) } else rgb(205, 208, 216)
    }

    @Test fun aiRunsOnSubjectCropAndStaysOffFrameAndBackground() = runBlocking {
        var seenW = 0; var seenH = 0
        // fake model: ink everywhere along the frame, a ring around the subject, and a stroke in the backdrop
        val model = LineArtModel { _, mw, mh ->
            seenW = mw; seenH = mh
            FloatArray(mw * mh) { i ->
                val x = i % mw; val y = i / mw
                val frame = x < 4 || y < 4 || x >= mw - 4 || y >= mh - 4
                val cx = (x - mw / 2f) / (mw * 0.40f); val cy = (y - mh / 2f) / (mh * 0.42f)
                val ring = kotlin.math.abs(sqrt(cx * cx + cy * cy) - 1f) < 0.012f
                if (frame || ring) 1f else 0f
            }
        }
        val d = SketchPipeline.run(scene(), w, h, PipelineSettings(), model)
        assertEquals("AI", SketchPipeline.lastLineSource)
        assertTrue("model should see the crop at >= 600px long side, saw ${seenW}x$seenH", maxOf(seenW, seenH) >= 600)
        assertTrue("crop must be narrower than the frame", seenW.toFloat() / seenH < w.toFloat() / h * 0.9f)
        assertTrue(d.outline.isNotEmpty())
        for (s in d.outline) {
            var i = 0
            while (i < s.points.size) {
                assertTrue(s.points[i] > 3f && s.points[i] < d.width - 4f && s.points[i + 1] > 3f && s.points[i + 1] < d.height - 4f)
                i += 2
            }
        }
    }
}
