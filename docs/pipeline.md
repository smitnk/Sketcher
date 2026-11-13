# Generation pipeline contract (backend agents MUST follow)

All files in package `com.smitnk.sketcher.image.pipeline` (dir app/src/main/java/com/smitnk/sketcher/image/pipeline/)
are PURE KOTLIN (no android.*) except `OnnxLineArtModel.kt`.
Images are ARGB `IntArray` (row-major), plus `w`, `h`. Ink maps are `FloatArray` size w*h, 0 = paper, 1 = full ink.
`Stroke`, `BrushType`, `GeneratedDrawing` stay where they are (image/StrokeGenerator.kt, image/ColorStrokeGenerator.kt).

```kotlin
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

object LineArtVectorizer {                   // B2
    fun vectorize(ink: FloatArray, w: Int, h: Int, detail: Float, lineWidth: Float, maxStrokes: Int): List<Stroke>
}

object CartoonLineExtractor {                // B4
    fun cartoonScore(argb: IntArray, w: Int, h: Int): Float   // 0..1 ; >= 0.6 treated as cartoon
    fun inkMap(argb: IntArray, w: Int, h: Int, detail: Float): FloatArray
}

object ShadingGenerator {                    // B3
    fun generate(argb: IntArray, w: Int, h: Int, settings: PipelineSettings, ink: FloatArray?): List<Stroke>
}

object SketchPipeline {                      // B1 (orchestrator)
    suspend fun run(argb: IntArray, w: Int, h: Int, settings: PipelineSettings,
                    model: LineArtModel?, progress: (stage: String, fraction: Float) -> Unit = { _, _ -> }): GeneratedDrawing
}
```
CLASSIC = existing StrokeGenerator (B5 improves it). Colour fill = existing ColorStrokeGenerator.generateFill.
Model: assets/models/lineart.onnx — informative-drawings (MIT, Chan et al. 2022), ONNX export by josephrocca.
Input float32 [1,3,H,W] RGB 0..1 (H,W multiples of 8), output [1,1,H,W] where 1 = white paper, lower = ink → ink = 1 - out.
