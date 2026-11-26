package com.smitnk.sketcher.ui.settings

import kotlin.math.roundToInt

/** Speed / quality presets. BALANCED must equal today's defaults (PipelineSettings, PostPreview.MAX_SIDE, VideoExporter). */
enum class SpeedPreset(val label: String, val hint: String) {
    FAST("Fast", "Quicker and lighter. Less detail."),
    BALANCED("Balanced", "The usual mix of speed and detail."),
    BEST("Best quality", "More detail. Slower and bigger."),
}

/** [bitrateFactor] = video bits per pixel per second (VideoExporter uses w * h * 5). */
data class SpeedValues(val workingSize: Int, val previewMaxSide: Int, val maxStrokes: Int, val bitrateFactor: Int)

object SpeedQuality {
    /** Live values read by canvas / export code. */
    @Volatile var previewMaxSide = 720
    @Volatile var bitrateFactor = 5

    fun base(p: SpeedPreset) = when (p) {
        SpeedPreset.FAST -> SpeedValues(768, 540, 2500, 3)
        SpeedPreset.BALANCED -> SpeedValues(1024, 720, 4000, 5)
        SpeedPreset.BEST -> SpeedValues(1280, 1080, 6000, 8)
    }

    /** [simplify] 0..1: 0 keeps all strokes of the preset, 1 keeps 40%. */
    fun values(p: SpeedPreset, simplify: Float = 0f): SpeedValues {
        val b = base(p)
        return b.copy(maxStrokes = (b.maxStrokes * (1f - 0.6f * simplify.coerceIn(0f, 1f))).roundToInt())
    }
}
