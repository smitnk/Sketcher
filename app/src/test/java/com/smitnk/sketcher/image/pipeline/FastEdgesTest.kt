package com.smitnk.sketcher.image.pipeline

import com.smitnk.sketcher.image.models.InkPolarity
import com.smitnk.sketcher.image.models.ModelSpecs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FastEdgesTest {
    /** Paper at 0.44 (TEED floor), a 3 px wide vertical line at 0.95, a faint isolated speck at 0.60. */
    private fun map(w: Int = 40, h: Int = 40): FloatArray {
        val p = FloatArray(w * h) { 0.44f }
        for (y in 0 until h) for (x in 19..21) p[y * w + x] = if (x == 20) 0.97f else 0.80f
        p[5 * w + 5] = 0.60f
        return p
    }

    @Test fun paperBecomesZeroAndLineKeepsInk() {
        val ink = FastEdges.refine(map(), 40, 40, 0.5f)
        assertEquals(0f, ink[10 * 40 + 2], 1e-6f)
        assertTrue(ink[20 * 40 + 20] > 0.9f)
    }

    @Test fun isolatedWeakSpeckIsRemoved() {
        val ink = FastEdges.refine(map(), 40, 40, 0.5f)
        assertEquals(0f, ink[5 * 40 + 5], 1e-6f)
    }

    @Test fun flanksAreAttenuatedRidgeKept() {
        val ink = FastEdges.refine(map(), 40, 40, 0.5f)
        val ridge = ink[20 * 40 + 20]
        val flank = ink[20 * 40 + 19]
        assertTrue("flank $flank < ridge $ridge", flank < ridge * 0.6f)
    }

    @Test fun flatMapGivesNoInk() {
        val ink = FastEdges.refine(FloatArray(64) { 0.3f }, 8, 8, 0.5f)
        assertTrue(ink.all { it == 0f })
    }

    @Test fun moreDetailKeepsAtLeastAsMuchInk() {
        val p = map()
        val lo = FastEdges.refine(p, 40, 40, 0f).sum()
        val hi = FastEdges.refine(p, 40, 40, 1f).sum()
        assertTrue(hi >= lo)
    }

    @Test fun fastSpecsAreEdgeHighAndMultipleOf8() {
        for (s in ModelSpecs.FAST) {
            assertTrue(s.fast)
            assertEquals(InkPolarity.INK_IS_HIGH, s.inkPolarity)
            assertEquals(1f, s.inkFromOutput(1f), 0f)
            assertEquals(0f, s.inkFromOutput(0f), 0f)
            val (w, h) = s.targetSize(1000, 700, 768)
            assertEquals(0, w % 8); assertEquals(0, h % 8)
        }
        assertTrue(ModelSpecs.TEED.isBundled)
        assertTrue(!ModelSpecs.MTEED.isBundled)
        assertEquals("OpenRAIL++ (TheMistoAI/MistoLine)", ModelSpecs.MTEED.licence)
        assertEquals(listOf("informative", "realistic", "coarse", "anime", "anilines"), ModelSpecs.ALL.map { it.id })
        assertTrue(ModelSpecs.byId("mteed") != null)
    }
}
