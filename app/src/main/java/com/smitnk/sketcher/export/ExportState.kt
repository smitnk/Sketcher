package com.smitnk.sketcher.export

/** What an export produces. Used to label progress and to report per-kind success. */
enum class ExportKind(val label: String, val runningLabel: String) {
    IMAGE("Image", "Saving image..."),
    LAYERS("Layers", "Saving layers..."),
    VIDEO("MP4", "Exporting MP4 video..."),
    GIF("GIF", "Exporting GIF..."),
    FRAMES("Frames", "Exporting frames..."),
}

/** Outcome of one exported file. [uri] is a MediaStore uri string when shareable. */
class ExportResult(
    val kind: ExportKind,
    val uri: String?,
    val path: String?,
    val ok: Boolean,
    val error: String? = null,
)

/**
 * Export lifecycle. Idle -> Running -> Done | Failed. Done and Failed never block anything; the next start
 * replaces them explicitly. Only Running (or a generation in progress) blocks a new start.
 */
sealed interface ExportState {
    data object Idle : ExportState

    data class Running(
        val kind: ExportKind,
        val progress: Float = 0f,
        val label: String = kind.runningLabel,
    ) : ExportState

    /** Finished (also when some files failed: see [Done.allOk]). */
    data class Done(val results: List<ExportResult>) : ExportState {
        val allOk: Boolean get() = results.isNotEmpty() && results.all { it.ok }
        val anyOk: Boolean get() = results.any { it.ok }
    }

    data class Failed(val reason: String) : ExportState
}

/** Why a new export cannot start now, or null when it can. [generating] = a drawing is being generated. */
fun ExportState.blockedReason(generating: Boolean): String? = when {
    generating -> "Wait for the drawing to finish generating before exporting"
    this is ExportState.Running -> "Another export is still running"
    else -> null
}

/** Human readable, per-file summary for the result dialog (one line per file, honest about failures). */
fun ExportState.Done.summary(): String = results.joinToString("\n") { r ->
    if (r.ok) (r.path ?: "${r.kind.label} saved") + (r.error?.let { "\n$it" } ?: "") else "${r.kind.label} failed: ${r.error ?: "unknown error"}"
}
