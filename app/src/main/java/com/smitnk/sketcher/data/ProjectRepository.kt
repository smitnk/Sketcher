package com.smitnk.sketcher.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import com.smitnk.sketcher.export.Renderer
import com.smitnk.sketcher.model.Layer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

data class ProjectMeta(val id: String, val name: String, val updatedAt: Long, val thumbPath: String?)

class LoadedProject(val snapshot: ProjectSnapshot, val source: Bitmap?)

class ProjectRepository(context: Context) {
    private val root = File(context.applicationContext.filesDir, "projects")

    private fun dir(id: String) = File(root, id)

    private fun safeId(id: String): Boolean = id.isNotEmpty() && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    /**
     * Saves [snapshot] (take it with Project.toSnapshot() on the main thread first).
     * [source] is the background photo (JPEG 90). Returns the project id (new if [id] null).
     */
    suspend fun save(id: String?, name: String, snapshot: ProjectSnapshot, source: Bitmap?): String =
        withContext(Dispatchers.IO) {
            val pid = id?.takeIf { safeId(it) } ?: UUID.randomUUID().toString()
            val d = dir(pid)
            d.mkdirs()
            writeAtomic(File(d, "project.skch"), ProjectCodec.encode(snapshot))
            File(d, "name.txt").writeText(name)
            val src = File(d, "source.jpg")
            if (source != null && !source.isRecycled) {
                FileOutputStream(src).use { source.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            } else {
                src.delete()
            }
            writeThumb(d, snapshot, source)
            pid
        }

    suspend fun load(id: String): LoadedProject? = withContext(Dispatchers.IO) {
        if (!safeId(id)) return@withContext null
        val d = dir(id)
        val f = File(d, "project.skch")
        if (!f.exists()) return@withContext null
        val snap = try {
            ProjectCodec.decode(f.readBytes())
        } catch (e: Exception) {
            return@withContext null
        }
        val src = File(d, "source.jpg")
        val bmp = if (src.exists()) {
            BitmapFactory.decodeFile(src.absolutePath)?.let {
                if (it.isMutable) it else it.copy(Bitmap.Config.ARGB_8888, true)
            }
        } else {
            null
        }
        LoadedProject(snap, bmp)
    }

    /** Newest first. */
    suspend fun list(): List<ProjectMeta> = withContext(Dispatchers.IO) {
        val dirs = root.listFiles { f -> f.isDirectory } ?: return@withContext emptyList()
        dirs.mapNotNull { d ->
            val proj = File(d, "project.skch")
            if (!proj.exists()) return@mapNotNull null
            val nameFile = File(d, "name.txt")
            val name = if (nameFile.exists()) nameFile.readText() else "Untitled"
            val thumb = File(d, "thumb.png")
            ProjectMeta(d.name, name, proj.lastModified(), if (thumb.exists()) thumb.absolutePath else null)
        }.sortedByDescending { it.updatedAt }
    }

    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        safeId(id) && dir(id).deleteRecursively()
    }

    suspend fun rename(id: String, newName: String): Boolean = withContext(Dispatchers.IO) {
        if (!safeId(id) || !File(dir(id), "project.skch").exists()) return@withContext false
        File(dir(id), "name.txt").writeText(newName)
        true
    }

    private fun writeAtomic(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    private fun writeThumb(d: File, s: ProjectSnapshot, source: Bitmap?) {
        try {
            val scale = 360f / max(s.width, s.height).coerceAtLeast(1)
            val tw = max(1, (s.width * scale).roundToInt())
            val th = max(1, (s.height * scale).roundToInt())
            val bmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(Color.WHITE)
            c.scale(scale, scale)
            if (source != null && !source.isRecycled && s.backgroundVisible) {
                val p = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
                p.alpha = (s.backgroundOpacity * 255f).roundToInt().coerceIn(0, 255)
                c.drawBitmap(source, null, android.graphics.RectF(0f, 0f, s.width.toFloat(), s.height.toFloat()), p)
            }
            val layers = s.layers.map { ls ->
                Layer(ls.id, ls.name).also { l ->
                    l.visible = ls.visible
                    l.opacity = ls.opacity
                    val brushes = com.smitnk.sketcher.image.BrushType.values()
                    for (st in ls.strokes) {
                        l.strokes.add(
                            com.smitnk.sketcher.image.Stroke(
                                st.points, st.color, st.width,
                                brushes.getOrElse(st.brush) { com.smitnk.sketcher.image.BrushType.PEN }, st.alpha,
                            ),
                        )
                    }
                }
            }
            Renderer.drawLayers(c, s.width, s.height, layers)
            FileOutputStream(File(d, "thumb.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
        } catch (e: Exception) {
            // thumbnail is best-effort
        }
    }
}
