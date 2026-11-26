package com.smitnk.sketcher.ui.settings

import com.smitnk.sketcher.image.pipeline.PipelineSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedQualityTest {
    @Test fun balancedEqualsTodaysDefaults() {
        val d = PipelineSettings()
        val v = SpeedQuality.values(SpeedPreset.BALANCED)
        assertEquals(d.workingSize, v.workingSize)
        assertEquals(d.maxStrokes, v.maxStrokes)
        assertEquals(720, v.previewMaxSide) // PostPreview.MAX_SIDE
        assertEquals(5, v.bitrateFactor) // VideoExporter w*h*5
    }

    @Test fun fastIsLighterAndBestIsHeavier() {
        val f = SpeedQuality.values(SpeedPreset.FAST)
        val b = SpeedQuality.values(SpeedPreset.BEST)
        assertTrue(f.workingSize < b.workingSize && f.maxStrokes < b.maxStrokes && f.previewMaxSide < b.previewMaxSide)
    }

    @Test fun simplifyReducesStrokes() {
        assertEquals(1600, SpeedQuality.values(SpeedPreset.BALANCED, 1f).maxStrokes)
    }
}
