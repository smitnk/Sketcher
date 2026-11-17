package com.smitnk.sketcher.image.pipeline

import kotlinx.coroutines.runBlocking
import kotlin.math.max
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

    @Test fun faintInkIsNormalisedByPercentile() {
        // thin / light model: strongest ink only 0.3
        val ink = FloatArray(10_000) { if (it % 20 == 0) 0.30f else 0.01f }
        SketchPipeline.normaliseInk(ink)
        assertTrue("faint line becomes clearly visible", ink[0] > 0.8f)
        assertEquals("paper noise stays clean", 0f, ink[1], 0f)
    }

    @Test fun strongModelNotDimmedNorGreyWashMuddy() {
        // heavy model: strong ink 0.98 over ~3% of the area, light grey wash 0.12 elsewhere
        val ink = FloatArray(10_000) { if (it % 33 == 0) 0.98f else 0.12f }
        SketchPipeline.normaliseInk(ink)
        assertTrue(ink[0] > 0.95f)
        assertTrue("grey wash must not turn into visible mud: ${ink[1]}", ink[1] < 0.12f)
    }

    @Test fun faintRegionIsLiftedLocallyAndBlankPaperStaysClean() {
        val w = 400; val h = 200
        val ink = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val line = (y % 10 == 0)
            if (x < 200 && line) ink[y * w + x] = 0.95f          // strong region (sets global hi)
            if (x in 240..360 && line) ink[y * w + x] = 0.15f    // faint region
            // x in 200..239 and 361..399: blank paper with tiny noise
            if ((x in 200..239 || x >= 361) && (x + y) % 7 == 0) ink[y * w + x] = 0.02f
        }
        val before = ink.copyOf()
        SketchPipeline.normaliseInk(ink, null, w, h)
        val faint = ink[100 * w + 300]
        assertTrue("faint line lifted: $faint", faint > 0.45f)
        assertTrue("strong line kept", ink[100 * w + 100] > 0.95f)
        assertEquals("blank paper clean", 0f, ink[(100 + 1) * w + 390], 0f)
        assertTrue(before[100 * w + 300] < 0.2f)
        var maxBlank = 0f
        for (y in 0 until h) for (x in 380 until 400) maxBlank = max(maxBlank, ink[y * w + x])
        assertEquals("paper far from ink stays zero", 0f, maxBlank, 0f)
    }

    @Test fun autoCutAndStrongInkGuard() {
        assertEquals(0.75f, SketchPipeline.AUTO_CARTOON_CUT, 0f)
        val sub = SubjectCrop.full(100, 100)
        val few = FloatArray(10_000).also { for (i in 0 until 20) it[i] = 1f }     // 0.2% < 0.4%
        val enough = FloatArray(10_000).also { for (i in 0 until 60) it[i] = 1f }  // 0.6%
        assertTrue(!SketchPipeline.hasStrongInk(few, 100, 100, sub, null))
        assertTrue(SketchPipeline.hasStrongInk(enough, 100, 100, sub, null))
    }

    @Test fun autoFallsBackToAiWhenCartoonInkIsAlmostAbsent() = runBlocking {
        // smooth shaded figure: even if it scored as cartoon, nearly no strong ink => AI must be used
        var called = false
        val model = LineArtModel { _, mw, mh -> called = true; FloatArray(mw * mh) }
        SketchPipeline.run(scene(), w, h, PipelineSettings(), model)
        assertTrue(called)
        assertEquals("AI", SketchPipeline.lastLineSource)
        assertTrue(SketchPipeline.lastTimings.containsKey("total"))
        assertTrue(SketchPipeline.lastTimings.containsKey("model"))
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

    @Test fun cartoonFusionIsMaxOfAiAndCartoonAndSilhouetteOnlyReinforces() {
        val pw = 100; val ph = 100
        val bg = BooleanArray(pw * ph) { val x = it % pw; val y = it / pw; x < 20 || x >= 80 || y < 20 || y >= 80 }
        val sub = SubjectCrop.Result(pw, ph, 0, 0, pw, ph, bg, 0.64f)
        val ai = FloatArray(pw * ph).also { it[50 * pw + 40] = 0.6f; it[50 * pw + 50] = 0.2f }
        val cm = FloatArray(pw * ph).also { it[50 * pw + 50] = 0.9f; it[50 * pw + 22] = 0.9f }
        val alpha = BooleanArray(pw * ph) { bg[it] }
        val out = SketchPipeline.fuseCartoonInk(ai, cm, pw, ph, sub, null, alpha)
        assertEquals(0.6f, out[50 * pw + 40], 1e-6f)      // AI detail kept
        assertEquals(0.9f, out[50 * pw + 50], 1e-6f)      // cartoon outline wins
        assertTrue(out[50 * pw + 20] >= 0.9f)             // edge with a nearby line (22 is within 3px of 20) is reinforced
        assertEquals(0f, out[80 * pw / 2 + 5], 0f)        // backdrop untouched
        assertEquals(0f, out[30 * pw + 20], 0f)           // silhouette far from any line is NOT invented
        assertEquals(0f, out[79 * pw + 50], 0f)
        val noAi = SketchPipeline.fuseCartoonInk(null, cm, pw, ph, sub, null, alpha)
        assertEquals(0.9f, noAi[50 * pw + 50], 1e-6f); assertEquals(0f, noAi[50 * pw + 40], 0f)
        // no colour data and no alpha -> no silhouette at all
        val plain = SketchPipeline.fuseCartoonInk(null, cm, pw, ph, sub)
        assertEquals(0f, plain[50 * pw + 20], 0f)
    }

    @Test fun flattenAlphaCompositesOntoWhiteAndDerivesMask() {
        val n = 40 * 40
        val px = IntArray(n) { if (it % 40 < 20) 0x00000000 else (0xFF shl 24) or 0x112233 }
        val (flat, mask) = SketchPipeline.flattenAlpha(px, 40, 40, null)
        assertEquals(-1, flat[0]); assertEquals(px[39], flat[39])
        assertTrue(mask!![0]); assertTrue(!mask[39])
        val opaque = IntArray(n) { 0xFF112233.toInt() }
        val (same, none) = SketchPipeline.flattenAlpha(opaque, 40, 40, null)
        assertTrue(same === opaque); assertEquals(null, none)
    }

    @Test fun smoothMaskRemovesStairSteps() {
        val w = 60; val h = 60
        val a = BooleanArray(w * h) { val x = it % w; val y = it / w; x > 20 + (y / 3) % 2 * 2 }   // jagged vertical edge
        val s = SketchPipeline.smoothMask(a, w, h, 3)
        val edges = (10 until 50).map { y -> (0 until w).first { s[y * w + it] } }
        assertTrue(edges.max() - edges.min() <= 1)
    }

    @Test fun cartoonRunsModelToo() = runBlocking {
        var called = false
        val model = LineArtModel { _, mw, mh -> called = true; FloatArray(mw * mh) }
        SketchPipeline.run(scene(), w, h, PipelineSettings(lineSource = LineSource.CARTOON), model)
        assertTrue(called)
        assertEquals("CARTOON", SketchPipeline.lastLineSource)
        called = false
        SketchPipeline.run(scene(), w, h, PipelineSettings(lineSource = LineSource.CARTOON), null)
        assertTrue(!called)
        assertEquals("CARTOON", SketchPipeline.lastLineSource)
    }

    @Test fun edgeInkFindsColourEdgeBetweenEquallyBrightRegions() {
        val w = 40; val h = 40
        val px = IntArray(w * h) { if (it % w < 20) rgb(200, 60, 60) else rgb(60, 200, 60) }
        val e = SketchPipeline.edgeInk(px, w, h)
        assertTrue("edge at the seam", e[20 * w + 20] > 0.2f)
        assertEquals("flat region has no edge", 0f, e[20 * w + 5], 1e-6f)
    }

    @Test fun cleanVectorUsesFullDetail() {
        assertEquals(1f, SketchPipeline.cleanDetail(PipelineSettings(outlineStyle = OutlineStyle.CLEAN_VECTOR), 0.3f), 0f)
        assertEquals(0.3f, SketchPipeline.cleanDetail(PipelineSettings(), 0.3f), 0f)
    }

    @Test fun classicWithoutModelTracesRenderWithEdgeInkRaster() {
        val d = runBlocking { SketchPipeline.run(scene(), w, h, PipelineSettings(lineSource = LineSource.CLASSIC), null) }
        assertEquals("CLASSIC", SketchPipeline.lastLineSource)
        assertTrue("figure outline traced", d.outline.isNotEmpty())
    }
}
