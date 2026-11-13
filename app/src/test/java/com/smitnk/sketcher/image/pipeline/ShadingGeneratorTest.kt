package com.smitnk.sketcher.image.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShadingGeneratorTest {

    private val w = 400
    private val h = 300

    /** White background; a dark disc (subject) with a lighter rim, ink = disc outline. */
    private fun scene(): Pair<IntArray, FloatArray> {
        val px = IntArray(w * h) { 0xFFF4F4F4.toInt() }
        val ink = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val d = Math.hypot((x - 200).toDouble(), (y - 150).toDouble())
            if (d < 90) px[y * w + x] = if (x > 200) 0xFF202020.toInt() else 0xFFB0B0B0.toInt()
            if (d in 88.0..91.0) ink[y * w + x] = 1f
        }
        return px to ink
    }

    private fun gen(level: ShadingLevel, style: ShadingStyle = ShadingStyle.HATCH, ignoreBg: Boolean = true) =
        scene().let { (px, ink) -> ShadingGenerator.generate(px, w, h, PipelineSettings(shading = level, shadingStyle = style, ignoreBackground = ignoreBg), ink) }

    @Test fun noneGivesNothing() = assertTrue(gen(ShadingLevel.NONE).isEmpty())

    @Test fun shadesDarkSubjectOnly() {
        val s = gen(ShadingLevel.MEDIUM)
        assertTrue(s.isNotEmpty())
        for (st in s) for (i in st.points.indices step 2) {
            val d = Math.hypot((st.points[i] - 200).toDouble(), (st.points[i + 1] - 150).toDouble())
            assertTrue("stroke point outside subject", d < 95)
        }
        // darker (right) half gets the hatching
        val right = s.count { it.points[0] > 200 }
        assertTrue(right > s.size / 2)
    }

    @Test fun flatImageGetsNoShading() {
        val px = IntArray(w * h) { 0xFFF0F0F0.toInt() }
        val s = ShadingGenerator.generate(px, w, h, PipelineSettings(shading = ShadingLevel.HEAVY), FloatArray(w * h))
        assertTrue(s.isEmpty())
    }

    @Test fun deterministicAndCapped() {
        assertEquals(gen(ShadingLevel.HEAVY).map { it.points.toList() }, gen(ShadingLevel.HEAVY).map { it.points.toList() })
        for (st in ShadingStyle.values()) assertTrue(gen(ShadingLevel.HEAVY, st).size <= 1500)
    }

    @Test fun heavierLevelMeansMoreStrokes() {
        assertTrue(gen(ShadingLevel.HEAVY).size >= gen(ShadingLevel.LIGHT).size)
    }

    @Test fun styleAndColour() {
        for (st in ShadingStyle.values()) {
            val s = gen(ShadingLevel.MEDIUM, st)
            assertTrue(s.isNotEmpty())
            assertTrue(s.all { it.color == 0xFF3A3A3A.toInt() && it.width in 1.0f..3.4f && it.points.size >= 4 })
        }
    }

    @Test fun cartoonFlatColourGivesNoShading() {
        val px = IntArray(w * h) { 0xFF3070D0.toInt() }
        val s = ShadingGenerator.generate(px, w, h, PipelineSettings(shading = ShadingLevel.MEDIUM), null, cartoon = true)
        assertTrue(s.isEmpty())
    }

    @Test fun cartoonCelShadowIsShaded() {
        val px = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        for (y in 40 until 260) for (x in 60 until 340) px[y * w + x] = if (x > 220) 0xFF2050A0.toInt() else 0xFF4080E0.toInt()
        val s = ShadingGenerator.generate(px, w, h, PipelineSettings(shading = ShadingLevel.MEDIUM), null, cartoon = true)
        assertTrue(s.isNotEmpty())
        assertTrue(s.all { st -> (0 until st.points.size step 2).all { st.points[it] > 200f } })
    }
}
