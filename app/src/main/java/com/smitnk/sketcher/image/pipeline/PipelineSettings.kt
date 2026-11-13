package com.smitnk.sketcher.image.pipeline

enum class LineSource { AUTO, AI, CARTOON, CLASSIC }       // AUTO: cartoon detector -> CARTOON else AI (CLASSIC if model unavailable)
enum class ShadingLevel { NONE, LIGHT, MEDIUM, HEAVY }
enum class ShadingStyle { HATCH, CROSS_HATCH, SCRIBBLE }

data class PipelineSettings(
    val lineSource: LineSource = LineSource.AUTO,
    val detail: Float = 0.5f,            // 0..1 more/less lines
    val lineWidth: Float = 2.2f,         // px at working resolution
    val shading: ShadingLevel = ShadingLevel.NONE,   // default: outline only
    val shadingStyle: ShadingStyle = ShadingStyle.HATCH,
    val colourFill: Boolean = false,
    val fillColors: Int = 10,
    val fillSpacing: Float = 6f,
    val ignoreBackground: Boolean = true, // don't shade flat/background regions
    val maxStrokes: Int = 4000,
    val workingSize: Int = 1024,          // long side for vector output
)

fun interface LineArtModel {                 // B1 implements OnnxLineArtModel (android) ; tests use fakes
    /** returns ink map (w*h, 0..1) for the given image at the same size */
    fun infer(argb: IntArray, w: Int, h: Int): FloatArray
}
