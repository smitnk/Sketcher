package com.smitnk.sketcher.ui.generate

import com.smitnk.sketcher.image.pipeline.GapClosing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CelPreviewTest {
    @Test fun downscalesAndFillsEveryPixelOpaque() {
        val w = 400; val h = 200
        val px = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            val inBox = x in 100..300 && y in 50..150
            val edge = inBox && (x < 106 || x > 294 || y < 56 || y > 144)
            if (edge) 0xFF000000.toInt() else if (inBox) 0xFFFF0000.toInt() else 0xFFFFFFFF.toInt()
        }
        val r = celPreview(px, w, h, GapClosing.MEDIUM, 8)
        assertEquals(128, r.width); assertEquals(64, r.height)
        assertEquals(r.width * r.height, r.pixels.size)
        assertTrue(r.pixels.all { (it ushr 24) == 255 })
    }
}
