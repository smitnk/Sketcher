package com.smitnk.sketcher.export

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.smitnk.sketcher.image.BrushType
import com.smitnk.sketcher.image.Stroke
import com.smitnk.sketcher.model.Layer
import com.smitnk.sketcher.model.Project
import kotlin.math.max
import kotlin.math.min
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

    /** Same budget logic as AnimationController.allowances, as a pure function of [progress] (0..1). */
    fun allowancesFor(layers: List<Layer>, progress: Float): Map<Long, Float> {
        var total = 0f
        for (l in layers) if (l.visible && l.inAnimation) total += l.totalLength()
        var budget = progress.coerceIn(0f, 1f) * total
        val map = HashMap<Long, Float>()
        for (l in layers.sortedBy { it.animPriority }) {
            if (!l.visible || !l.inAnimation) continue
            val len = l.totalLength()
            map[l.id] = budget.coerceIn(0f, len)
            budget -= len
        }
        return map
    }

    /** Draws the animation state at [progress] (0..1), as the on-screen animation would. */
    fun drawPartial(canvas: Canvas, width: Int, height: Int, layers: List<Layer>, progress: Float) {
        drawLayers(canvas, width, height, layers, allowances = allowancesFor(layers, progress))
    }

    /**
     * THE single place where a stroke's paint and geometry are set up for its brush. Everything
     * (screen, live stroke, animation, PNG/MP4/GIF export) draws through [drawLayers] -> here.
     * Geometry depends only on arc length, so animation truncation never makes texture flicker.
     */
    fun drawStroke(canvas: Canvas, paint: Paint, path: Path, stroke: Stroke, limit: Float) {
        val p = stroke.points
        if (p.size < 2) return
        val w = stroke.width
        val a = (android.graphics.Color.alpha(stroke.color) / 255f) * stroke.alpha.coerceIn(0f, 1f)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = stroke.color
        when (stroke.brush) {
            BrushType.PEN -> {
                buildPath(path, p, limit)
                paint.strokeWidth = w
                paint.alpha = a255(a)
                canvas.drawPath(path, paint)
            }
            BrushType.PENCIL -> {
                val pw = max(1f, w * 0.6f)
                buildJitterPath(path, p, limit, seedOf(stroke), step = max(2f, pw * 1.2f), amp = pw * 0.45f)
                paint.strokeWidth = pw
                paint.alpha = a255(a)
                canvas.drawPath(path, paint)
            }
            BrushType.MARKER -> {
                buildPath(path, p, limit)
                paint.strokeCap = Paint.Cap.SQUARE
                paint.strokeWidth = w * 2f
                paint.alpha = a255(a)
                canvas.drawPath(path, paint)
            }
            BrushType.SOFT -> {
                // Airbrush look without BlurMaskFilter (hardware-canvas safe): 3 stacked widths.
                buildPath(path, p, limit)
                paint.alpha = a255(a * 0.5f)
                for (f in SOFT_PASSES) {
                    paint.strokeWidth = w * f
                    canvas.drawPath(path, paint)
                }
            }
            BrushType.HATCH -> {
                val half = max(3f, w * 1.5f)
                buildHatchPath(path, p, limit, spacing = max(3f, w * 1.5f), half = half)
                paint.strokeWidth = max(1f, w * 0.15f)
                paint.alpha = a255(a)
                canvas.drawPath(path, paint)
            }
        }
        paint.alpha = 255
    }

    private val SOFT_PASSES = floatArrayOf(2f, 1.3f, 0.7f)

    private fun a255(a: Float) = (a * 255f).roundToInt().coerceIn(0, 255)

    private fun seedOf(s: Stroke): Int {
        val p = s.points
        var h = p[0].toBits() * 31 + p[1].toBits()
        h = h * 31 + s.width.toBits()
        return h
    }

    /** Deterministic noise in -1..1 for (seed, k). */
    private fun noise(seed: Int, k: Int): Float {
        var h = seed xor (k * -0x61c88647)
        h = (h xor (h ushr 16)) * -0x7a143595
        h = (h xor (h ushr 13)) * -0x3d4d51cb
        h = h xor (h ushr 16)
        return (h and 0xFFFF) / 32767.5f - 1f
    }

    /** Wobbly polyline sampled every [step] units of arc length, offset sideways by up to [amp]. */
    private fun buildJitterPath(path: Path, p: FloatArray, limit: Float, seed: Int, step: Float, amp: Float) {
        path.rewind()
        var started = false
        var k = 0
        var next = 0f
        var traveled = 0f
        var ex = p[0]
        var ey = p[1]
        var i = 2
        while (i < p.size && traveled < limit) {
            val dx = p[i] - p[i - 2]
            val dy = p[i + 1] - p[i - 1]
            val full = sqrt(dx * dx + dy * dy)
            i += 2
            if (full <= 0f) continue
            val d = min(full, limit - traveled)
            val nx = -dy / full
            val ny = dx / full
            while (next <= traveled + d) {
                val t = (next - traveled) / full
                val off = noise(seed, k++) * amp
                val x = p[i - 4] + dx * t + nx * off
                val y = p[i - 3] + dy * t + ny * off
                if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
                next += step
            }
            traveled += d
            ex = p[i - 4] + dx * (d / full)
            ey = p[i - 3] + dy * (d / full)
        }
        if (!started) path.moveTo(p[0], p[1])
        path.lineTo(ex, ey)
        if (p.size == 2) path.lineTo(p[0], p[1])
    }

    /** Short parallel 45-degree lines every [spacing] units along the polyline. */
    private fun buildHatchPath(path: Path, p: FloatArray, limit: Float, spacing: Float, half: Float) {
        path.rewind()
        val h = half * 0.70710678f
        var next = 0f
        var traveled = 0f
        var any = false
        var i = 2
        while (i < p.size && traveled < limit) {
            val dx = p[i] - p[i - 2]
            val dy = p[i + 1] - p[i - 1]
            val full = sqrt(dx * dx + dy * dy)
            i += 2
            if (full <= 0f) continue
            val d = min(full, limit - traveled)
            while (next <= traveled + d) {
                val t = (next - traveled) / full
                val x = p[i - 4] + dx * t
                val y = p[i - 3] + dy * t
                path.moveTo(x - h, y + h)
                path.lineTo(x + h, y - h)
                any = true
                next += spacing
            }
            traveled += d
        }
        if (!any) {
            path.moveTo(p[0] - h, p[1] + h)
            path.lineTo(p[0] + h, p[1] - h)
        }
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
