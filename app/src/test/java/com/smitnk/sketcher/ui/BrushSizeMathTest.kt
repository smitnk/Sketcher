package com.smitnk.sketcher.ui

import com.smitnk.sketcher.ui.canvas.BrushSizeMath
import org.junit.Assert.assertEquals
import org.junit.Test

class BrushSizeMathTest {
    @Test fun sliderMapsLogEndsAndRoundTrips() {
        assertEquals(1f, BrushSizeMath.toPx(0f), 1e-4f)
        assertEquals(200f, BrushSizeMath.toPx(1f), 1e-2f)
        assertEquals(16f, BrushSizeMath.toPx(BrushSizeMath.toSlider(16f)), 1e-3f)
        assertEquals(14.14f, BrushSizeMath.toPx(0.5f), 0.01f) // log feel: far below linear 100
    }

    @Test fun screenRadiusFollowsZoom() {
        val fit = 0.8f // screen px per canvas px at zoom 1
        assertEquals(4f, BrushSizeMath.screenRadius(20f, fit * 0.5f), 1e-4f)
        assertEquals(8f, BrushSizeMath.screenRadius(20f, fit * 1f), 1e-4f)
        assertEquals(24f, BrushSizeMath.screenRadius(20f, fit * 3f), 1e-4f)
    }
}
