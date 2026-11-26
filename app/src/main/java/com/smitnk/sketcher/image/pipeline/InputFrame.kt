package com.smitnk.sketcher.image.pipeline

import kotlin.math.roundToInt

/** Aspect presets for the "Adjust photo" step. ratio = width / height; null = free or original. */
enum class AspectPreset(val label: String, val ratio: Float?) {
    FREE("Free", null), SQUARE("1:1", 1f), SHORTS("9:16", 9f / 16f), WIDE("16:9", 16f / 9f), PORTRAIT("4:5", 4f / 5f), ORIGINAL("Original", null)
}

/** Output long side presets. CUSTOM uses [InputFrame.customLongSide]. */
enum class SizePreset(val label: String, val longSide: Int) { SMALL("Small (fast)", 720), MEDIUM("Medium", 1080), LARGE("Large", 1440), CUSTOM("Custom", 0) }

/**
 * User choice from the "Adjust photo" step. Crop is normalised (0..1) to the loaded photo. [auto] = true means
 * today's automatic crop + size (all other fields ignored).
 */
data class InputFrame(
    val auto: Boolean = true,
    val aspect: AspectPreset = AspectPreset.ORIGINAL,
    val left: Float = 0f, val top: Float = 0f, val right: Float = 1f, val bottom: Float = 1f,
    val size: SizePreset = SizePreset.MEDIUM,
    val customLongSide: Int = 1080,
) {
    val longSide: Int get() = if (size == SizePreset.CUSTOM) customLongSide.coerceIn(MIN_SIDE, MAX_SIDE) else size.longSide

    /** Final canvas size for a photo of [photoW] x [photoH]. */
    fun canvasSize(photoW: Int, photoH: Int): Pair<Int, Int> {
        val cw = ((right - left).coerceIn(0f, 1f) * photoW).coerceAtLeast(1f)
        val ch = ((bottom - top).coerceIn(0f, 1f) * photoH).coerceAtLeast(1f)
        val k = longSide / maxOf(cw, ch)
        return maxOf(1, (cw * k).roundToInt()) to maxOf(1, (ch * k).roundToInt())
    }

    fun sizeLabel(photoW: Int, photoH: Int): String = canvasSize(photoW, photoH).let { "${it.first} × ${it.second}" }
    val isSlow: Boolean get() = longSide > SLOW_SIDE

    /** Compact text form for preferences; read back with [decode]. */
    fun encode(): String = listOf(auto, aspect.name, left, top, right, bottom, size.name, customLongSide).joinToString(",")

    companion object {
        const val MIN_SIDE = 512; const val MAX_SIDE = 2048; const val SLOW_SIDE = 1440

        /** Inverse of [encode]; null or broken text gives the default (automatic) frame. */
        fun decode(text: String?): InputFrame {
            val p = text?.split(",") ?: return InputFrame()
            if (p.size != 8) return InputFrame()
            return try {
                val l = p[2].toFloat(); val t = p[3].toFloat(); val r = p[4].toFloat(); val b = p[5].toFloat()
                val ok = l in 0f..1f && t in 0f..1f && r in 0f..1f && b in 0f..1f && r > l && b > t
                InputFrame(
                    p[0].toBooleanStrict(), AspectPreset.valueOf(p[1]),
                    if (ok) l else 0f, if (ok) t else 0f, if (ok) r else 1f, if (ok) b else 1f,
                    SizePreset.valueOf(p[6]), p[7].toInt().coerceIn(MIN_SIDE, MAX_SIDE),
                )
            } catch (_: IllegalArgumentException) { InputFrame() }
        }

        /** Largest centred crop (normalised) of a photo with [ratio] (w/h); null ratio = full photo. */
        fun centredCrop(photoW: Int, photoH: Int, ratio: Float?): FloatArray {
            if (ratio == null || photoW <= 0 || photoH <= 0) return floatArrayOf(0f, 0f, 1f, 1f)
            val pr = photoW.toFloat() / photoH
            return if (pr > ratio) { val w = ratio / pr; floatArrayOf((1 - w) / 2, 0f, (1 + w) / 2, 1f) }
            else { val h = pr / ratio; floatArrayOf(0f, (1 - h) / 2, 1f, (1 + h) / 2) }
        }
    }
}
