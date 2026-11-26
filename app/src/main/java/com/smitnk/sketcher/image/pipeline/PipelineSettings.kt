package com.smitnk.sketcher.image.pipeline

enum class LineSource { AUTO, AI, CARTOON, CLASSIC, FLOW_LINES, FAST_EDGES }       // AUTO: cartoon detector -> CARTOON else AI (CLASSIC if model unavailable)
enum class ShadingLevel { NONE, LIGHT, MEDIUM, HEAVY }
enum class ShadingStyle { HATCH, CROSS_HATCH, SCRIBBLE }

/** PENCIL_REVEAL keeps the exact AI/cartoon ink raster and animates it with reveal strokes; CLEAN_VECTOR draws vector pen strokes. */
enum class OutlineStyle { PENCIL_REVEAL, CLEAN_VECTOR }

/** Colour fill look: STROKES = hatched colour strokes; FLAT_CEL = trapped-ball flat cel regions ([LineFill]). */
enum class FillStyle { STROKES, FLAT_CEL }

/** CHARACTER_ONLY = today's output; WITH_BACKGROUND adds "Background outline" (+ optional colour) layers below. */
enum class BackgroundMode { CHARACTER_ONLY, WITH_BACKGROUND }

/** When the background layers draw relative to the character. */
enum class BackgroundOrder { FIRST, LAST, SAME_TIME }

data class PipelineSettings(
    val lineSource: LineSource = LineSource.AUTO,
    val detail: Float = 0.5f,            // 0..1 more/less lines
    val lineWidth: Float = 2.2f,         // px at working resolution
    val shading: ShadingLevel = ShadingLevel.NONE,   // default: outline only
    val shadingStyle: ShadingStyle = ShadingStyle.HATCH,
    val colourFill: Boolean = false,
    val fillColors: Int = 10,
    val fillSpacing: Float = 6f,
    val fillStyle: FillStyle = FillStyle.STROKES,
    val gapClosing: GapClosing = GapClosing.MEDIUM, // FLAT_CEL only: how wide line gaps the fill may bridge
    val cleanInk: Boolean = false,        // AniLines only: binarise the ink map at 0.5
    val ignoreBackground: Boolean = true, // don't shade flat/background regions
    val maxStrokes: Int = 4000,
    val workingSize: Int = 1024,          // long side for vector output
    val outlineStyle: OutlineStyle = OutlineStyle.PENCIL_REVEAL,
    val background: BackgroundMode = BackgroundMode.CHARACTER_ONLY,
    val backgroundOrder: BackgroundOrder = BackgroundOrder.FIRST,
    val backgroundColour: Boolean = false,
)

fun interface LineArtModel {                 // B1 implements OnnxLineArtModel (android) ; tests use fakes
    /** returns ink map (w*h, 0..1) for the given image at the same size */
    fun infer(argb: IntArray, w: Int, h: Int): FloatArray
}
