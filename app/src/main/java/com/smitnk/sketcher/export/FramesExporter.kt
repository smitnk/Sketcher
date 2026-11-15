package com.smitnk.sketcher.export

import android.content.Context
import com.smitnk.sketcher.model.FrameOps
import com.smitnk.sketcher.model.Layer
import java.io.IOException

/** What "Export frames" produces. */
enum class FramesExportKind { PNG_SEQUENCE, GIF, VIDEO }

/** Frame-by-frame exports (finished drawing of every frame). GIF / MP4 live in [GifExporter] / [VideoExporter]. */
object FramesExporter {

    /**
     * Writes one transparent PNG per frame to Pictures/Sketcher/<project>/frame_0001.png ... (zero padded, in
     * order). [frames] are layer snapshots taken on the main thread. Blocking; call off the main thread.
     * Returns "<folder> (N PNG files)"; throws [IOException] when a file cannot be written (earlier files stay).
     */
    fun pngSequence(
        context: Context,
        width: Int,
        height: Int,
        frames: List<List<Layer>>,
        projectName: String,
        onProgress: (Float) -> Unit,
        checkCancel: () -> Unit,
    ): String {
        val sub = FrameOps.safeName(projectName)
        var folder = "Pictures/Sketcher/$sub"
        for ((i, layers) in frames.withIndex()) {
            checkCancel()
            val bmp = Exporter.render(width, height, layers, 1, transparent = true)
            val where = try {
                Exporter.save(
                    context, bmp, ExportFormat.PNG, sub, checkCancel,
                    fileName = FrameOps.sequenceName(i, frames.size), subDir = sub,
                )
            } finally {
                bmp.recycle()
            }
            where ?: throw IOException("Could not write frame ${i + 1} of ${frames.size}")
            if (i == 0) folder = where.substringBeforeLast('/')
            onProgress((i + 1).toFloat() / frames.size)
        }
        return "$folder/ (${frames.size} PNG files)"
    }
}
