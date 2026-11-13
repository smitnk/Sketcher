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
        return alignUp(w.toDouble() * longSide / long) to alignUp(h.toDouble() * longSide / long)
    }

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
    )
    val COARSE = ModelSpec(
        id = "coarse", name = "Coarse",
        description = "ControlNet coarse lineart. Bolder, simpler lines.",
        fileName = "lineart_coarse_fp16.onnx",
        inputNorm = InputNorm.ZERO_ONE, outputRange = OutputRange.ZERO_ONE,
        inkPolarity = InkPolarity.INK_IS_LOW, sizeMultiple = 8,
        downloadUrl = "$BASE/lineart_coarse_fp16.onnx",
    )
    val ANIME = ModelSpec(
        id = "anime", name = "Anime",
        description = "ControlNet anime lineart UNet (fp16). Best for drawings and cartoons.",
        fileName = "lineart_anime_fp16.onnx",
        inputNorm = InputNorm.MINUS_ONE_ONE, outputRange = OutputRange.MINUS_ONE_ONE,
        inkPolarity = InkPolarity.INK_IS_LOW, sizeMultiple = 256,
        downloadUrl = "https://github.com/smitnk/Sketcher/releases/download/models-v1/lineart_anime_fp16.onnx",
    )

    val ALL: List<ModelSpec> = listOf(INFORMATIVE, REALISTIC, COARSE, ANIME)
    const val DEFAULT_ID = "informative"

    fun byId(id: String): ModelSpec? = ALL.firstOrNull { it.id == id }
}
