package com.smitnk.sketcher.ui

import com.smitnk.sketcher.ui.adjust.Corner
import com.smitnk.sketcher.ui.adjust.CropRect
import com.smitnk.sketcher.ui.adjust.dragCorner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CropMathTest {
    private val full = CropRect(0f, 0f, 1f, 1f)

    @Test fun freeDragMovesOnlyThatCorner() {
        val r = dragCorner(full, Corner.TL, 0.25f, 0.4f, null, 1f)
        assertEquals(0.25f, r.l, 1e-5f); assertEquals(0.4f, r.t, 1e-5f); assertEquals(1f, r.r, 1e-5f); assertEquals(1f, r.b, 1e-5f)
    }

    @Test fun clampsInsidePhotoAndMinSize() {
        assertEquals(CropRect(0f, 0f, 1f, 0.1f), dragCorner(full, Corner.BR, 3f, -2f, null, 1f, 0.1f))
        val b = dragCorner(full, Corner.TL, 0.99f, 0.99f, null, 1f, 0.1f)
        assertEquals(0.9f, b.l, 1e-5f)
        assertEquals(0.9f, b.t, 1e-5f)
    }

    @Test fun ratioLockKeepsPixelRatio() {
        val aspect = 1.5f // 3:2 photo
        val r = dragCorner(CropRect(0.1f, 0.1f, 0.5f, 0.5f), Corner.BR, 0.9f, 0.6f, 1f, aspect)
        assertEquals(1f, ((r.r - r.l) * aspect) / (r.b - r.t), 1e-4f)
        assertEquals(0.1f, r.l, 1e-6f)
        assertEquals(0.1f, r.t, 1e-6f)
        assertTrue(r.r <= 1f + 1e-5f && r.b <= 1f + 1e-5f)
    }

    @Test fun ratioLockStaysInsidePhoto() {
        val r = dragCorner(CropRect(0.4f, 0.4f, 0.6f, 0.6f), Corner.BR, 5f, 5f, 16f / 9f, 1f)
        assertTrue(r.r <= 1f + 1e-5f && r.b <= 1f + 1e-5f)
        assertEquals(16f / 9f, (r.r - r.l) / (r.b - r.t), 1e-3f)
    }
}
