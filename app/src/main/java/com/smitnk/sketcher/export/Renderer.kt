package com.smitnk.sketcher.export

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.smitnk.sketcher.image.Stroke
import com.smitnk.sketcher.model.Layer
import com.smitnk.sketcher.model.Project
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Draws layers with android.graphics. Used by both the on-screen canvas and the exporter. */
object Renderer {

    fun drawBackground(canvas: Canvas, project: Project) {
        val bmp = project.background ?: return
        if (!project.backgroundVisible) return
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        paint.alpha = (project.backgroundOpacity * 255f).roundToInt().coerceIn(0, 255)
        canvas.drawBitmap(bmp, null, RectF(0f, 0f, project.width.toFloat(), project.height.toFloat()), paint)
    }

    /**
     * @param allowances per-layer revealed length (animation); null = everything.
     * @param ignoreLayerState when true, hidden layers are drawn and opacity is ignored
     *        (used for exporting a single layer).
     */
    fun drawLayers(
        canvas: Canvas,
        width: Int,
        height: Int,
        layers: List<Layer>,
        allowances: Map<Long, Float>? = null,
        live: Stroke? = null,
        liveLayerId: Long = -1L,
        ignoreLayerState: Boolean = false,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        val path = Path()

        for (layer in layers) {
            if (!ignoreLayerState && !layer.visible) continue
            val alpha = if (ignoreLayerState) 255 else (layer.opacity * 255f).roundToInt().coerceIn(0, 255)
            if (alpha == 0) continue

            var budget = allowances?.get(layer.id) ?: Float.MAX_VALUE
            val saved = if (alpha < 255) {
                canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), alpha)
            } else {
                -1
            }
            for (stroke in layer.strokes) {
                if (budget <= 0f) break
                drawStroke(canvas, paint, path, stroke, budget)
                budget -= stroke.length
            }
            if (live != null && layer.id == liveLayerId) {
                drawStroke(canvas, paint, path, live, Float.MAX_VALUE)
            }
            if (saved >= 0) canvas.restoreToCount(saved)
        }
    }

    private fun drawStroke(canvas: Canvas, paint: Paint, path: Path, stroke: Stroke, limit: Float) {
        if (stroke.points.size < 2) return
        buildPath(path, stroke.points, limit)
        paint.color = stroke.color
        paint.strokeWidth = stroke.width
        canvas.drawPath(path, paint)
    }

    /** Fills [path] with the polyline, cut off after [limit] units of length. */
    private fun buildPath(path: Path, p: FloatArray, limit: Float) {
        path.rewind()
        path.moveTo(p[0], p[1])
        var remaining = limit
        var i = 2
        while (i < p.size) {
            val dx = p[i] - p[i - 2]
            val dy = p[i + 1] - p[i - 1]
            val d = sqrt(dx * dx + dy * dy)
            if (d <= remaining) {
                path.lineTo(p[i], p[i + 1])
                remaining -= d
            } else {
                val t = remaining / d
                path.lineTo(p[i - 2] + dx * t, p[i - 1] + dy * t)
                break
            }
            i += 2
        }
        // A single point still draws a dot with a round cap.
        if (p.size == 2) path.lineTo(p[0], p[1])
    }
}
