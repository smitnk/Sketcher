package com.smitnk.sketcher.image.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSpecTest {
    @Test fun registryHasExpectedIdsAndOneSourceEach() {
        assertEquals(listOf("informative", "realistic", "coarse", "anime"), ModelSpecs.ALL.map { it.id })
        assertTrue(ModelSpecs.INFORMATIVE.isBundled)
        assertTrue(ModelSpecs.ANIME.downloadUrl!!.contains("releases/download/models-v1"))
    }

    @Test fun zeroOneInkIsLow() {
        val s = ModelSpecs.REALISTIC
        assertEquals(1f, s.inkFromOutput(0f), 1e-6f)
        assertEquals(0f, s.inkFromOutput(1f), 1e-6f)
        assertEquals(0f, s.inkFromOutput(1.7f), 1e-6f)
        assertEquals(1f, s.inkFromOutput(-0.3f), 1e-6f)
    }

    @Test fun animeTanhOutputMapsToInk() {
        val s = ModelSpecs.ANIME
        assertEquals(1f, s.inkFromOutput(-1f), 1e-6f)
        assertEquals(0.5f, s.inkFromOutput(0f), 1e-6f)
        assertEquals(0f, s.inkFromOutput(1f), 1e-6f)
    }

    @Test fun inkIsHighKeepsValue() {
        val s = ModelSpecs.COARSE.copy(inkPolarity = InkPolarity.INK_IS_HIGH)
        assertEquals(0.25f, s.inkFromOutput(0.25f), 1e-6f)
    }

    @Test fun inputNormalisation() {
        val z = ModelSpecs.INFORMATIVE
        assertEquals(1f, 255 * z.inputScale + z.inputOffset, 1e-6f)
        val a = ModelSpecs.ANIME
        assertEquals(-1f, 0 * a.inputScale + a.inputOffset, 1e-6f)
        assertEquals(1f, 255 * a.inputScale + a.inputOffset, 1e-6f)
    }

    @Test fun targetSizeMultiple8Rounds() {
        val (tw, th) = ModelSpecs.INFORMATIVE.targetSize(1000, 500, 768)
        assertEquals(768, tw)
        assertEquals(384, th)
        val (w2, h2) = ModelSpecs.INFORMATIVE.targetSize(300, 401, 768)
        assertEquals(0, w2 % 8); assertEquals(0, h2 % 8)
        assertEquals(768, h2)
    }

    @Test fun targetSizeMultiple256RoundsUp() {
        val (tw, th) = ModelSpecs.ANIME.targetSize(1000, 700, 768)
        assertEquals(768, tw)
        assertEquals(768, th) // 537.6 -> ceil to 768
        val (w2, h2) = ModelSpecs.ANIME.targetSize(1000, 500, 768)
        assertEquals(768, w2)
        assertEquals(512, h2) // 384 -> 512
    }

    @Test(expected = IllegalArgumentException::class)
    fun specNeedsExactlyOneSource() {
        ModelSpec("x", "x", "x", "x.onnx", InputNorm.ZERO_ONE, OutputRange.ZERO_ONE, InkPolarity.INK_IS_LOW, 8)
    }
}
