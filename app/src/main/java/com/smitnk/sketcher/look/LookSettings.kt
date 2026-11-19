package com.smitnk.sketcher.look

/**
 * "Look" presets: per-project rendering style options.
 * Portions adapted from Lemo-Opuscar by Lemomo (LemoLab),
 * https://github.com/lemomo-ai/lemo-opuscar, MIT License, modified (ported to Kotlin).
 * Only MIT-licensed code (JS engines) was used as reference — not the CC BY-NC STYLE.md docs.
 */
enum class StrokeFeel { CLEAN, WHITEBOARD_MARKER, PEN_PLOTTER, SCRATCH_BOIL }
enum class Paper { PLAIN, CREAM, PENCIL_TOOTH, ENGRAVING }
enum class PostFilter { NONE, ONE_BIT_ATKINSON, ONE_BIT_BAYER, HALFTONE }
/** Pen sprite drawn at the tip while the drawing animates. Do not reorder: the ordinal is persisted. */
enum class PenStyle { PENCIL, MARKER, BRUSH }

data class LookSettings(
    val feel: StrokeFeel = StrokeFeel.CLEAN,
    val paper: Paper = Paper.PLAIN,
    val misregisterColour: Boolean = false, // riso/woodblock: offset colour-fill layer vs outline
    val post: PostFilter = PostFilter.NONE,
    val onTwos: Boolean = false,            // animate/redraw at 12 drawings/s
    val seed: Int = 1,
    // Motion (pen sprite + follow camera); all OFF by default so existing projects look as before.
    val showPen: Boolean = false,
    val penStyle: PenStyle = PenStyle.PENCIL,
    val cameraFollow: Boolean = false,
    val cameraZoom: Float = 1.6f,       // 1.0..2.5
) {
    companion object {
        /** Named presets shown in the UI. */
        val PRESETS: List<Pair<String, LookSettings>> = listOf(
            "Clean" to LookSettings(),
            "Whiteboard" to LookSettings(
                feel = StrokeFeel.WHITEBOARD_MARKER, paper = Paper.PLAIN,
                showPen = true, penStyle = PenStyle.MARKER, cameraFollow = true, cameraZoom = 1.4f,
            ),
            "Pen plotter" to LookSettings(feel = StrokeFeel.PEN_PLOTTER, paper = Paper.CREAM, showPen = true),
            "Colored pencil" to LookSettings(paper = Paper.PENCIL_TOOTH),
            "Scratch film" to LookSettings(feel = StrokeFeel.SCRATCH_BOIL, onTwos = true, paper = Paper.CREAM),
            "Woodblock / riso" to LookSettings(paper = Paper.CREAM, misregisterColour = true),
            "1-bit dither" to LookSettings(post = PostFilter.ONE_BIT_ATKINSON),
            "Halftone" to LookSettings(post = PostFilter.HALFTONE),
        )
    }
}
