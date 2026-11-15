package com.smitnk.sketcher.ui

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver

/** CI / automation actions that run once after a generation finishes. */
enum class EditorAutoAction { SHOW_LAYERS, AUTO_EXPORT, AUTO_VIDEO }

/**
 * One-shot editor events. Each is tied to the project session ([sessionId]) that produced it;
 * the editor ignores events from an older session and the VM drops queued ones on reset.
 */
sealed interface EditorEvent {
    val sessionId: Int

    data class OpenStyle(override val sessionId: Int) : EditorEvent
    data class ShowLayers(override val sessionId: Int) : EditorEvent
    data class AutoExport(override val sessionId: Int) : EditorEvent
    data class AutoVideo(override val sessionId: Int) : EditorEvent
}

/** The single editor overlay that may be open at a time; back closes it. */
sealed interface EditorOverlay {
    data object None : EditorOverlay
    data object Layers : EditorOverlay
    data object Style : EditorOverlay
    data object Export : EditorOverlay
    data class Rename(val text: String) : EditorOverlay
    data object MlSettings : EditorOverlay
}

val EditorOverlaySaver: Saver<EditorOverlay, Any> = listSaver(
    save = { o ->
        when (o) {
            EditorOverlay.None -> listOf("none")
            EditorOverlay.Layers -> listOf("layers")
            EditorOverlay.Style -> listOf("style")
            EditorOverlay.Export -> listOf("export")
            is EditorOverlay.Rename -> listOf("rename", o.text)
            EditorOverlay.MlSettings -> listOf("ml")
        }
    },
    restore = { l ->
        when (l[0]) {
            "layers" -> EditorOverlay.Layers
            "style" -> EditorOverlay.Style
            "export" -> EditorOverlay.Export
            "rename" -> EditorOverlay.Rename(l.getOrElse(1) { "" })
            "ml" -> EditorOverlay.MlSettings
            else -> EditorOverlay.None
        }
    },
)

/** Export that waits for the storage permission (pre-Q). Survives activity recreation. */
enum class PendingExportKind { NONE, LAYERS_SEPARATELY, IMAGE, ANIMATION }

data class PendingExport(
    val kind: PendingExportKind = PendingExportKind.NONE,
    val scale: Int = 1,
    val format: String = "",
    val transparent: Boolean = false,
    val layerOnly: Boolean = false,
    val anim: String = "",
)

val PendingExportSaver: Saver<PendingExport, Any> = listSaver(
    save = { p -> listOf(p.kind.name, p.scale, p.format, p.transparent, p.layerOnly, p.anim) },
    restore = { l ->
        PendingExport(
            kind = runCatching { PendingExportKind.valueOf(l[0] as String) }.getOrDefault(PendingExportKind.NONE),
            scale = l[1] as Int,
            format = l[2] as String,
            transparent = l[3] as Boolean,
            layerOnly = l[4] as Boolean,
            anim = l[5] as String,
        )
    },
)
