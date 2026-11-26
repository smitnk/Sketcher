package com.smitnk.sketcher.image.pipeline

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputFrameTest {
    @Test fun presetsGiveLongSide() {
        assertEquals(720, InputFrame(size = SizePreset.SMALL).longSide)
        assertEquals(1080, InputFrame().longSide)
        assertEquals(1440, InputFrame(size = SizePreset.LARGE).longSide)
    }

    @Test fun customIsClamped() {
        assertEquals(512, InputFrame(size = SizePreset.CUSTOM, customLongSide = 100).longSide)
        assertEquals(2048, InputFrame(size = SizePreset.CUSTOM, customLongSide = 9000).longSide)
        assertEquals(1300, InputFrame(size = SizePreset.CUSTOM, customLongSide = 1300).longSide)
    }

    @Test fun labelForShortsCrop() {
        val c = InputFrame.centredCrop(4000, 3000, AspectPreset.SHORTS.ratio)
        val f = InputFrame(false, AspectPreset.SHORTS, c[0], c[1], c[2], c[3], SizePreset.CUSTOM, 1920)
        assertEquals("1080 × 1920", f.sizeLabel(4000, 3000))
        assertEquals(1080 to 1920, f.canvasSize(4000, 3000))
    }

    @Test fun fullPhotoKeepsAspect() {
        assertEquals(1080 to 810, InputFrame().canvasSize(4000, 3000))
        assertEquals(720 to 720, InputFrame(size = SizePreset.SMALL).canvasSize(300, 300)) // small photos scale up
    }

    @Test fun centredCrop() {
        assertArrayEquals(floatArrayOf(0f, 0f, 1f, 1f), InputFrame.centredCrop(100, 50, null), 1e-6f)
        assertArrayEquals(floatArrayOf(0.25f, 0f, 0.75f, 1f), InputFrame.centredCrop(200, 100, 1f), 1e-6f)
        assertArrayEquals(floatArrayOf(0f, 0.25f, 1f, 0.75f), InputFrame.centredCrop(100, 200, 1f), 1e-6f)
        assertArrayEquals(floatArrayOf(0f, 0f, 1f, 1f), InputFrame.centredCrop(0, 0, 1f), 1e-6f)
    }

    @Test fun slowAbove1440() {
        assertFalse(InputFrame(size = SizePreset.LARGE).isSlow)
        assertTrue(InputFrame(size = SizePreset.CUSTOM, customLongSide = 1441).isSlow)
    }

    @Test fun encodeDecode() {
        val f = InputFrame(false, AspectPreset.WIDE, 0.1f, 0.2f, 0.9f, 0.8f, SizePreset.CUSTOM, 1500)
        assertEquals(f, InputFrame.decode(f.encode()))
        assertEquals(InputFrame(), InputFrame.decode(null))
        assertEquals(InputFrame(), InputFrame.decode("garbage"))
        assertEquals(InputFrame(), InputFrame.decode("true,NOPE,0,0,1,1,MEDIUM,1080"))
    }
}
