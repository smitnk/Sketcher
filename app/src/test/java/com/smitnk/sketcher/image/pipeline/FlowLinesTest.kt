package com.smitnk.sketcher.image.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class FlowLinesTest {
    private val n = 256

    private fun disc(): IntArray = IntArray(n * n) {
        val dx = it % n - n / 2f; val dy = it / n - n / 2f
        if (sqrt(dx * dx + dy * dy) < 70f) 0xFF202020.toInt() else 0xFFF0F0F0.toInt()
    }

    @Test fun circleGivesContinuousRing() {
        val ink = FlowLines.inkMap(disc(), n, n)
        // every angle: some ink within 4px of radius 70 (continuous ring, no gaps)
        for (deg in 0 until 360 step 3) {
            val a = deg * PI / 180
            var hit = false
            for (r in 66..74) {
                val x = (n / 2 + r * cos(a)).toInt(); val y = (n / 2 + r * sin(a)).toInt()
                if (ink[y * n + x] > 0.5f) hit = true
            }
            assertTrue("gap at $deg deg", hit)
        }
    }

    @Test fun flatInteriorAndBackgroundAreClean() {
        val ink = FlowLines.inkMap(disc(), n, n)
        var interior = 0; var outside = 0
        for (y in 0 until n) for (x in 0 until n) {
            val dx = x - n / 2f; val dy = y - n / 2f; val r = sqrt(dx * dx + dy * dy)
            if (r < 55f && ink[y * n + x] > 0.5f) interior++
            if (r > 90f && ink[y * n + x] > 0.5f) outside++
        }
        assertEquals(0, interior)
        assertEquals(0, outside)
    }

    @Test fun outputIsInUnitRangeAndSizeMatchesInput() {
        val w = 300; val h = 200
        val ink = FlowLines.inkMap(IntArray(w * h) { if (it % w < 150) -1 else 0xFF000000.toInt() }, w, h)
        assertEquals(w * h, ink.size)
        assertTrue(ink.all { it in 0f..1f })
    }

    @Test fun largeInputIsDownscaledAndFast() {
        val big = 1600
        val px = IntArray(big * big) { val dx = it % big - big / 2f; val dy = it / big - big / 2f
            if (sqrt(dx * dx + dy * dy) < 500f) 0xFF000000.toInt() else -1 }
        val ink = FlowLines.inkMap(px, big, big, FlowLines.Params(maxSide = 256))
        assertEquals(big * big, ink.size)
        assertTrue(ink[big / 2 * big + big / 2 + 500] > 0.3f || ink[big / 2 * big + big / 2 + 499] > 0.3f || ink[big / 2 * big + big / 2 + 501] > 0.3f)
    }

    @Test fun detailMapsToTau() {
        assertEquals(0.85f, FlowLines.paramsForDetail(0.5f).tau, 1e-4f)
        assertTrue(FlowLines.paramsForDetail(1f).tau > FlowLines.paramsForDetail(0f).tau)
    }
}
