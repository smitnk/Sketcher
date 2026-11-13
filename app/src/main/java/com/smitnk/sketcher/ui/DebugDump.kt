package com.smitnk.sketcher.ui

import android.app.Application
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.smitnk.sketcher.export.Exporter
import com.smitnk.sketcher.export.Renderer
import com.smitnk.sketcher.image.GeneratedDrawing
import com.smitnk.sketcher.image.Stroke
import com.smitnk.sketcher.image.pipeline.LineArtModel
import com.smitnk.sketcher.image.pipeline.PipelineSettings
import com.smitnk.sketcher.image.pipeline.SubjectCrop
import com.smitnk.sketcher.model.Layer
import java.io.File
import kotlin.math.max

/**
 * Debug-only diagnostics (FLAG_DEBUGGABLE builds): after each generation writes the pipeline's intermediate
 * images and numbers to getExternalFilesDir("debug")/<runLabel>/ so CI emulator runs can be inspected.
 */
object DebugDump {
    private const val TAG = "SketcherGen"

    @Volatile var runLabel: String = "run"
    @Volatile private var rawInk: FloatArray? = null
    @Volatile private var rawW = 0
    @Volatile private var rawH = 0
    @Volatile private var inferMs = 0L
    @Volatile var layersRef: List<Layer>? = null

    fun enabled(app: Application) = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** Wraps the model so the raw ink map of the run is captured (no-op in release builds). */
    fun wrap(app: Application, m: LineArtModel): LineArtModel {
        if (!enabled(app)) return m
        rawInk = null
        return LineArtModel { argb, w, h ->
            val t = android.os.SystemClock.elapsedRealtime()
            val ink = m.infer(argb, w, h)
            inferMs = android.os.SystemClock.elapsedRealtime() - t
            rawInk = ink.copyOf(); rawW = w; rawH = h
            ink
        }
    }

    /** Fraction of outline/shading/fill stroke length shown at progress 1 (should be 1.0). */
    fun revealedFraction(): Float = try {
        val layers = layersRef ?: return -1f
        val allow = Renderer.allowancesFor(layers, 1f)
        var total = 0f; var shown = 0f
        for (l in layers) {
            val len = l.totalLength()
            total += len
            shown += allow[l.id]?.let { minOf(it, len) } ?: len
        }
        if (total <= 0f) 1f else shown / total
    } catch (t: Throwable) { -1f }

    fun logAnimEnd(progress: Float) {
        android.util.Log.i(TAG, "ANIM_END progress=$progress revealedFraction=${revealedFraction()}")
    }

    /** Call off the main thread. [layers] is a snapshot of project.layers taken after applyDrawing. */
    fun afterGeneration(
        app: Application, drawing: GeneratedDrawing, source: Bitmap, ps: PipelineSettings, layers: List<Layer>,
        modelId: String, accelLabel: String?, fallback: String?, vmInferMs: Long?, genMs: Long, lineSource: String,
    ) {
        if (!enabled(app)) return
        try {
            val dir = File(app.getExternalFilesDir("debug"), runLabel).apply { deleteRecursively(); mkdirs() }
            val dw = drawing.width; val dh = drawing.height
            val ink = rawInk
            var stats = "no raw ink (non-AI source)"
            if (ink != null) {
                val px = IntArray(rawW * rawH) { val g = ((1f - ink[it].coerceIn(0f, 1f)) * 255f).toInt(); Color.rgb(g, g, g) }
                save(Bitmap.createBitmap(px, rawW, rawH, Bitmap.Config.ARGB_8888), File(dir, "ink_raw.png"))
                var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE; var sum = 0.0
                for (v in ink) { if (v < mn) mn = v; if (v > mx) mx = v; sum += v }
                stats = "min=$mn mean=${sum / ink.size} max=$mx (${rawW}x$rawH)"
            }
            val tex = layers.firstOrNull { it.inkTexture != null }?.inkTexture
            if (tex != null && !tex.isRecycled) {
                val b = Bitmap.createBitmap(tex.width, tex.height, Bitmap.Config.ARGB_8888)
                Canvas(b).apply { drawColor(Color.WHITE); drawBitmap(tex, 0f, 0f, null) }
                save(b, File(dir, "outline_texture.png"))
            }
            val reveal = drawing.revealWidth
            save(strokesBitmap(drawing.outline, dw, dh, 0f), File(dir, "strokes.png"))
            save(strokesBitmap(drawing.outline, dw, dh, reveal), File(dir, "coverage.png"))

            // crop / background mask (recomputed on the working-size image like the pipeline does)
            var cropInfo = "n/a"
            try {
                val k = ps.workingSize.toFloat() / max(source.width, source.height)
                val sb = if (k < 1f) Bitmap.createScaledBitmap(source, (source.width * k).toInt().coerceAtLeast(1), (source.height * k).toInt().coerceAtLeast(1), true) else source
                val px = IntArray(sb.width * sb.height)
                sb.getPixels(px, 0, sb.width, 0, 0, sb.width, sb.height)
                val s = SubjectCrop.detect(px, sb.width, sb.height)
                cropInfo = "x0=${s.x0} y0=${s.y0} x1=${s.x1} y1=${s.y1} of ${s.w}x${s.h} bgFraction=${s.bgFraction}"
            } catch (t: Throwable) { cropInfo = "failed: $t" }

            File(dir, "crop.txt").writeText(
                "crop: $cropInfo\nlineSource=$lineSource\nmodel=$modelId\naccelActive=$accelLabel fallback=$fallback\n" +
                    "inferenceMs=$vmInferMs (wrapped=$inferMs) genMs=$genMs\nink: $stats\n" +
                    "strokes: outline=${drawing.outline.size} shading=${drawing.shading.size} fill=${drawing.fill.size}\n" +
                    "inkW=${drawing.inkW} inkH=${drawing.inkH} drawing=${dw}x$dh revealWidth=$reveal\nsettings=$ps\n",
            )

            // render-path check through the real renderer
            val visible = layers.filter { it.visible }
            val fin = Exporter.render(dw, dh, visible, 1, false)
            save(fin, File(dir, "final_render.png"))
            val mid = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888)
            Canvas(mid).apply { drawColor(Color.WHITE); Renderer.drawPartial(this, dw, dh, visible, 0.5f) }
            save(mid, File(dir, "mid_render.png"))
            android.util.Log.i(TAG, "DEBUG_DUMP dir=${dir.absolutePath}")
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "DEBUG_DUMP failed", t)
        }
    }

    private fun strokesBitmap(strokes: List<Stroke>, w: Int, h: Int, widthOverride: Float): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.drawColor(Color.WHITE)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        val path = Path()
        for (s in strokes) {
            val pts = s.points
            if (pts.size < 4) continue
            path.rewind(); path.moveTo(pts[0], pts[1])
            var i = 2
            while (i + 1 < pts.size) { path.lineTo(pts[i], pts[i + 1]); i += 2 }
            p.strokeWidth = if (widthOverride > 0f) widthOverride else max(1f, s.width)
            c.drawPath(path, p)
        }
        return b
    }

    private fun save(b: Bitmap, f: File) {
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
