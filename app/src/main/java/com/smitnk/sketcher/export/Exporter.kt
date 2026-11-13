package com.smitnk.sketcher.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.smitnk.sketcher.model.Layer
import com.smitnk.sketcher.model.Project
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ExportFormat(val mime: String, val extension: String) {
    PNG("image/png", "png"),
    JPEG("image/jpeg", "jpg"),
}

object Exporter {

    /**
     * Renders visible layers (or only [onlyLayer], ignoring its visibility/opacity) to a bitmap.
     * The reference photo is never included.
     */
    fun render(project: Project, scale: Int, transparent: Boolean, onlyLayer: Layer? = null): Bitmap {
        val bmp = Bitmap.createBitmap(project.width * scale, project.height * scale, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        if (!transparent) canvas.drawColor(Color.WHITE)
        canvas.scale(scale.toFloat(), scale.toFloat())
        Renderer.drawLayers(
            canvas = canvas,
            width = project.width,
            height = project.height,
            layers = if (onlyLayer != null) listOf(onlyLayer) else project.layers.toList(),
            ignoreLayerState = onlyLayer != null,
        )
        return bmp
    }

    /** Saves to Pictures/Sketcher. Returns a human readable location, or null on failure. */
    fun save(context: Context, bitmap: Bitmap, format: ExportFormat, baseName: String): String? {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safe = baseName.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val fileName = "Sketcher_${safe}_$stamp.${format.extension}"
        val compressFormat = if (format == ExportFormat.PNG) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        val quality = if (format == ExportFormat.PNG) 100 else 95

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, format.mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Sketcher")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
                val ok = resolver.openOutputStream(uri)?.use { bitmap.compress(compressFormat, quality, it) } ?: false
                if (!ok) {
                    resolver.delete(uri, null, null)
                    return null
                }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                "Pictures/Sketcher/$fileName"
            } else {
                // Android 8/9: no storage permission requested, so use the app's own picture folder.
                val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: return null
                val file = File(dir, fileName)
                val ok = FileOutputStream(file).use { bitmap.compress(compressFormat, quality, it) }
                if (ok) file.absolutePath else null
            }
        } catch (e: Exception) {
            null
        }
    }
}
