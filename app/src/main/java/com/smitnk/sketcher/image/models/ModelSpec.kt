package com.smitnk.sketcher.image.models

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** How RGB bytes are mapped to the model input tensor. */
enum class InputNorm { ZERO_ONE, MINUS_ONE_ONE }

/** Value range the model emits (sigmoid = ZERO_ONE, tanh = MINUS_ONE_ONE). */
enum class OutputRange { ZERO_ONE, MINUS_ONE_ONE }

/** What a high/low output means. INK_IS_LOW: output 1 = paper, ink = 1 - out. */
enum class InkPolarity { INK_IS_LOW, INK_IS_HIGH }

/**
 * One line-art ONNX model and the exact pre/post-processing it needs.
 * Exactly one of [assetPath] (bundled) or [downloadUrl] must be set.
 * [sha256] (lower/upper hex) is verified after download when present; [sizeBytes] is only
 * an estimate for progress UI when the server sends no Content-Length.
 */
data class ModelSpec(
    val id: String,
    val name: String,
    val description: String,
    val fileName: String,
    val inputNorm: InputNorm,
    val outputRange: OutputRange,
    val inkPolarity: InkPolarity,
    val sizeMultiple: Int,
    val assetPath: String? = null,
    val downloadUrl: String? = null,
    val sha256: String? = null,
    val sizeBytes: Long? = null,
    /** Hard cap on model input pixels (tw*th); keeps peak memory bounded on phones. */
    val maxPixels: Int = 768 * 768,
    /** fp16 weights: CPU only, never XNNPACK/NNAPI, lighter graph optimisation. */
    val fp16: Boolean = false,
    /** Dynamic-int8 weights: CPU EP only (ORT docs: XNNPACK/NNAPI give nothing for ConvInteger/MatMulInteger). */
    val int8: Boolean = false,
    /** Belongs to the "Fast edges" engine (TEED/MTEED): not offered as a user-selectable AI line model. */
    val fast: Boolean = false,
    /** Belongs to the Cartoon engine (Anime2Sketch): not offered as a user-selectable AI line model. */
    val cartoon: Boolean = false,
    /** Licence shown next to optional downloads (null = permissive/bundled). */
    val licence: String? = null,
) {
    init {
        require(sizeMultiple > 0) { "sizeMultiple" }
        require((assetPath != null) != (downloadUrl != null)) { "exactly one of assetPath/downloadUrl" }
    }

    val isBundled: Boolean get() = assetPath != null

    /** Model input = byte * [inputScale] + [inputOffset]. */
    val inputScale: Float get() = if (inputNorm == InputNorm.ZERO_ONE) 1f / 255f else 1f / 127.5f
    val inputOffset: Float get() = if (inputNorm == InputNorm.ZERO_ONE) 0f else -1f

    /** Raw model output -> ink amount in 0..1 (1 = full ink). */
    fun inkFromOutput(v: Float): Float {
        val p = if (outputRange == OutputRange.MINUS_ONE_ONE) (v + 1f) * 0.5f else v
        val c = if (p < 0f) 0f else if (p > 1f) 1f else p
        return if (inkPolarity == InkPolarity.INK_IS_LOW) 1f - c else c
    }

    /**
     * Inference size for an image of [w]x[h] whose long side is scaled to about [longSide].
     * Small multiples (8) round to nearest; large ones (256, the anime UNet with 8 stride-2
     * stages) round up like controlnet_aux does (resize to ceil(side/256)*256).
     */
    fun targetSize(w: Int, h: Int, longSide: Int): Pair<Int, Int> {
        require(w > 0 && h > 0 && longSide > 0)
        val long = max(w, h)
        val step = max(sizeMultiple, 32).toDouble()
        var ls = longSide.toDouble()
        while (true) {
            val sw = w.toDouble() * ls / long
            val sh = h.toDouble() * ls / long
            val up = alignUp(sw) to alignUp(sh)
            if (up.first.toLong() * up.second <= maxPixels) return up
            val near = alignNearest(sw) to alignNearest(sh)
            if (near.first.toLong() * near.second <= maxPixels || ls <= sizeMultiple) return near
            ls = max(sizeMultiple.toDouble(), ls - step)
        }
    }

    private fun alignNearest(v: Double): Int = max(1, (v / sizeMultiple).roundToInt()) * sizeMultiple

    private fun alignUp(v: Double): Int {
        val m = sizeMultiple
        val n = if (m <= 8) (v / m).roundToInt() else ceil(v / m - 1e-9).toInt()
        return max(1, n) * m
    }
}

/**
 * Registry. Preprocessing verified against controlnet_aux (lineart/__init__.py,
 * lineart_anime/__init__.py): realistic/coarse are the same Generator (sigmoid output, input /255,
 * result 255 - out as ink); anime is a UNet (input /127.5-1, tanh output, line = out*127.5+127.5,
 * then 255 - line as ink, inputs padded/resized to multiples of 256).
 */
object ModelSpecs {
    private const val BASE = "https://github.com/smitnk/Sketcher/raw/models-lab"

    val INFORMATIVE = ModelSpec(
        id = "informative", name = "Informative drawings",
        description = "Bundled. Clean, artistic outlines. Fast and works offline.",
        fileName = "lineart.onnx",
        inputNorm = InputNorm.ZERO_ONE, outputRange = OutputRange.ZERO_ONE,
        inkPolarity = InkPolarity.INK_IS_LOW, sizeMultiple = 8,
        assetPath = "models/lineart.onnx",
    )
    val REALISTIC = ModelSpec(
        id = "realistic", name = "Realistic",
        description = "ControlNet lineart annotator. Detailed lines for photos.",
        fileName = "lineart_realistic_fp16.onnx",
        inputNorm = InputNorm.ZERO_ONE, outputRange = OutputRange.ZERO_ONE,
        inkPolarity = InkPolarity.INK_IS_LOW, sizeMultiple = 8,
        downloadUrl = "$BASE/lineart_realistic_fp16.onnx",
        fp16 = true,
    )
    val COARSE = ModelSpec(
        id = "coarse", name = "Coarse",
        description = "ControlNet coarse lineart. Bolder, simpler lines.",
        fileName = "lineart_coarse_fp16.onnx",
        inputNorm = InputNorm.ZERO_ONE, outputRange = OutputRange.ZERO_ONE,
        inkPolarity = InkPolarity.INK_IS_LOW, sizeMultiple = 8,
        downloadUrl = "$BASE/lineart_coarse_fp16.onnx",
        fp16 = true,
    )
    val ANIME = ModelSpec(
        id = "anime", name = "Anime",
        description = "ControlNet anime lineart UNet (fp16). Best for drawings and cartoons.",
        fileName = "lineart_anime_fp16.onnx",
        inputNorm = InputNorm.MINUS_ONE_ONE, outputRange = OutputRange.MINUS_ONE_ONE,
        inkPolarity = InkPolarity.INK_IS_LOW, sizeMultiple = 256,
        downloadUrl = "https://github.com/smitnk/Sketcher/releases/download/models-v1/lineart_anime_fp16.onnx",
        fp16 = true, maxPixels = 512 * 768,
    )

    /**
     * TEED (MIT, Xavier Soria Poma): 58K-parameter edge detector, BIPED epoch 5 (repo default checkpoint). The ONNX graph
     * takes RGB 0..1 and bakes in x255, BGR order, BIPED mean subtraction and sigmoid of the fused head (tools/model_lab/export_teed.py),
     * so it fits ZERO_ONE in / ZERO_ONE out; edge = HIGH. Paper level is ~0.44, FastEdges.refine normalises it.
     */
    val TEED = ModelSpec(
        id = "teed", name = "Fast edges",
        description = "Tiny edge model (TEED), near-instant, technical outlines.",
        fileName = "teed.onnx",
        inputNorm = InputNorm.ZERO_ONE, outputRange = OutputRange.ZERO_ONE,
        inkPolarity = InkPolarity.INK_IS_HIGH, sizeMultiple = 8,
        assetPath = "models/teed.onnx",
        maxPixels = 1024 * 1024, fast = true,
    )

    /** MTEED (Anyline, OpenRAIL++): same TEED net, no mean subtraction, sigmoid of the mean of the 4 logit maps. */
    val MTEED = ModelSpec(
        id = "mteed", name = "Fast edges+ (MTEED)",
        description = "Cleaner contours. Optional download, OpenRAIL++ licence.",
        fileName = "mteed.onnx",
        inputNorm = InputNorm.ZERO_ONE, outputRange = OutputRange.ZERO_ONE,
        inkPolarity = InkPolarity.INK_IS_HIGH, sizeMultiple = 8,
        downloadUrl = "$BASE/mteed.onnx", sizeBytes = 250_000L,
        maxPixels = 1024 * 1024, fast = true, licence = "OpenRAIL++ (TheMistoAI/MistoLine)",
    )

    /**
     * Anime2Sketch (MIT, Xiaoyu Xiang / Mukosame), "improved" artifact-free weights, fp16. UNet with 8 stride-2 stages
     * (sizeMultiple 256); input RGB -1..1 (graph input "input"), tanh output, ink = 1 - out. Cartoon engine only.
     */
    val ANIME2SKETCH = ModelSpec(
        id = "anime2sketch", name = "Bold cartoon lines",
        description = "Download ~96 MB. Bold clean coloring-book lines for flat cartoons. Not for photos/3D.",
        fileName = "anime2sketch_improved_fp16.onnx",
        inputNorm = InputNorm.MINUS_ONE_ONE, outputRange = OutputRange.MINUS_ONE_ONE,
        inkPolarity = InkPolarity.INK_IS_LOW, sizeMultiple = 256,
        downloadUrl = "https://github.com/smitnk/Sketcher/releases/download/models-v1/anime2sketch_improved_fp16.onnx",
        sizeBytes = 96_114_890L,
        fp16 = true, maxPixels = 768 * 768, cartoon = true, licence = "MIT (Mukosame/Anime2Sketch)",
    )

    /** User-selectable AI line models. */
    val ALL: List<ModelSpec> = listOf(INFORMATIVE, REALISTIC, COARSE, ANIME)
    const val DEFAULT_ID = "informative"

    /** Models of the Fast edges engine. */
    val FAST: List<ModelSpec> = listOf(TEED, MTEED)
    const val FAST_DEFAULT_ID = "teed"

    /** Optional models of the Cartoon engine. */
    val CARTOON: List<ModelSpec> = listOf(ANIME2SKETCH)
    const val CARTOON_BUILTIN_ID = "cartoon_builtin"

    /** Everything the ModelManager tracks. */
    val EVERY: List<ModelSpec> = ALL + FAST + CARTOON

    fun byId(id: String): ModelSpec? = EVERY.firstOrNull { it.id == id }
}
