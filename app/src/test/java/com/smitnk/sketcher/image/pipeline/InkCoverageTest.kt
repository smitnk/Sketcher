package com.smitnk.sketcher.image.pipeline

import com.smitnk.sketcher.image.Stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InkCoverageTest {
    private val w = 200
    private val h = 120

    private fun inkOf(draw: (FloatArray) -> Unit) = FloatArray(w * h).also(draw)

    @Test fun uncoveredInkGetsCoveredWithinRadius() {
        val ink = inkOf { a ->
            for (x in 20..180) { a[30 * w + x] = 1f; a[31 * w + x] = 1f }          // long line
            for (y in 50..100) for (x in 60..80) a[y * w + x] = 1f                // thick blob
            a[110 * w + 5] = 1f                                                    // lone pixel
            a[2 * w + 0] = 1f                                                      // border pixel
        }
        val existing = listOf(Stroke(floatArrayOf(20f, 30.5f, 100f, 30.5f), 0xFF000000.toInt(), 2f))
        val r = 5f
        val extra = InkCoverage.coverageStrokes(ink, w, h, existing, r)
        assertTrue(extra.isNotEmpty())
        assertEquals(0, InkCoverage.uncoveredCount(ink, w, h, existing + extra, r))
    }

    @Test fun nothingToAddWhenAlreadyCovered() {
        val ink = inkOf { a -> for (x in 20..100) a[30 * w + x] = 1f }
        val existing = listOf(Stroke(floatArrayOf(18f, 30.5f, 102f, 30.5f), 0xFF000000.toInt(), 2f))
        assertTrue(InkCoverage.coverageStrokes(ink, w, h, existing, 5f).isEmpty())
    }

    @Test fun visibilityZeroesBackground() {
        val ink = FloatArray(w * h) { 1f }
        val sub = SubjectCrop.Result(w, h, 0, 0, w, h, null, 0f)
        val bg = BooleanArray(w * h) { it % w < 100 }
        val v = SketchPipeline.inkVisibility(ink, w, h, sub, bg)
        assertEquals(0f, v[60 * w + 50], 0f)
        assertEquals(1f, v[60 * w + 150], 0f)
    }
}
