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

data class LookSettings(
    val feel: StrokeFeel = StrokeFeel.CLEAN,
    val paper: Paper = Paper.PLAIN,
    val misregisterColour: Boolean = false, // riso/woodblock: offset colour-fill layer vs outline
    val post: PostFilter = PostFilter.NONE,
    val onTwos: Boolean = false,            // animate/redraw at 12 drawings/s
    val seed: Int = 1,
) {
    companion object {
        /** Named presets shown in the UI. */
        val PRESETS: List<Pair<String, LookSettings>> = listOf(
            "Clean" to LookSettings(),
            "Whiteboard" to LookSettings(feel = StrokeFeel.WHITEBOARD_MARKER, paper = Paper.PLAIN),
            "Pen plotter" to LookSettings(feel = StrokeFeel.PEN_PLOTTER, paper = Paper.CREAM),
            "Colored pencil" to LookSettings(paper = Paper.PENCIL_TOOTH),
            "Scratch film" to LookSettings(feel = StrokeFeel.SCRATCH_BOIL, onTwos = true, paper = Paper.CREAM),
            "Woodblock / riso" to LookSettings(paper = Paper.CREAM, misregisterColour = true),
            "1-bit dither" to LookSettings(post = PostFilter.ONE_BIT_ATKINSON),
            "Halftone" to LookSettings(post = PostFilter.HALFTONE),
        )
    }
}
