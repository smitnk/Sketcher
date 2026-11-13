package com.smitnk.sketcher.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.smitnk.sketcher.image.BrushType
import com.smitnk.sketcher.image.Stroke
import com.smitnk.sketcher.model.Layer
import com.smitnk.sketcher.model.Project
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Draws layers with android.graphics. Used by both the on-screen canvas and the exporter. */
object Renderer {

    /** Per-thread scratch objects so draw loops allocate nothing (exports may run off the UI thread). */
    private class Scratch {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val path = Path()
        val rect = RectF()
        val bgPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    }

    private val scratchTl = object : ThreadLocal<Scratch>() {
        override fun initialValue() = Scratch()
    }

    fun drawBackground(canvas: Canvas, project: Project) {
        val bmp = project.background ?: return
        if (!project.backgroundVisible) return
        val sc = scratchTl.get()!!
        sc.bgPaint.alpha = (project.backgroundOpacity * 255f).roundToInt().coerceIn(0, 255)
        sc.rect.set(0f, 0f, project.width.toFloat(), project.height.toFloat())
        canvas.drawBitmap(bmp, null, sc.rect, sc.bgPaint)
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
    ) = drawLayerRange(canvas, width, height, layers, 0, layers.size, allowances, live, liveLayerId, ignoreLayerState)

    internal fun drawLayerRange(
        canvas: Canvas,
        width: Int,
        height: Int,
        layers: List<Layer>,
        from: Int,
        to: Int,
        allowances: Map<Long, Float>?,
        live: Stroke?,
        liveLayerId: Long,
        ignoreLayerState: Boolean = false,
    ) {
        val sc = scratchTl.get()!!
        val paint = sc.paint
        val path = sc.path
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND

        for (li in from until to) {
            val layer = layers[li]
            if (!ignoreLayerState && !layer.visible) continue
            val alpha = if (ignoreLayerState) 255 else (layer.opacity * 255f).roundToInt().coerceIn(0, 255)
            if (alpha == 0) continue

            var budget = allowances?.get(layer.id) ?: Float.MAX_VALUE
            val saved = if (alpha < 255) {
                canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), alpha)
            } else {
                -1
            }
            val strokes = layer.strokes
            for (i in 0 until strokes.size) {
                if (budget <= 0f) break
                val stroke = strokes[i]
                drawStroke(canvas, paint, path, stroke, budget)
                budget -= stroke.length
            }
            if (live != null && layer.id == liveLayerId) {
                drawStroke(canvas, paint, path, live, Float.MAX_VALUE)
            }
            if (saved >= 0) canvas.restoreToCount(saved)
        }
    }

    /**
     * Screen-side cache of the committed (fully revealed) bottom layers as a bitmap at screen scale.
     * Per frame only the in-progress stroke, partially animated layers and layers above them are drawn.
     * Invalidated by a cheap exact signature (layer ids, stroke counts, first/last stroke identity,
     * visibility, opacity, scale); a stroke appended to the top cached layer is drawn incrementally.
     * Not thread-safe: use from the UI thread only. Call [release] when the editor goes away.
     */
    class LayerCache {
        private var bmp: Bitmap? = null
        private val bmpCanvas = Canvas()
        private val bmpPaint = Paint()
        private var cachedS = 0f
        private var cachedK = -1
        private var sig = LongArray(0)
        private var tmp = LongArray(0)

        fun release() {
            bmp?.recycle(); bmp = null; cachedK = -1
        }

        /**
         * [canvas] must already be translated so (0,0) is the sheet's top-left in device pixels
         * (use an integer translation); [s] is device pixels per image pixel. Draws the layers only
         * (paper/background first, in the caller).
         */
        fun draw(
            canvas: Canvas,
            s: Float,
            pw: Int,
            ph: Int,
            layers: List<Layer>,
            allowances: Map<Long, Float>?,
            live: Stroke?,
            liveLayerId: Long,
        ) {
            val n = layers.size
            var k = n
            if (allowances != null) {
                for (i in 0 until n) {
                    val l = layers[i]
                    if (!l.visible) continue
                    val a = allowances[l.id]
                    if (a != null && a < l.totalLength()) { k = i; break }
                }
            }
            var liveIdx = -1
            if (live != null) {
                var lastVisible = -1
                for (i in 0 until n) {
                    if (layers[i].id == liveLayerId) liveIdx = i
                    if (layers[i].visible) lastVisible = i
                }
                if (liveIdx >= 0) k = min(k, if (liveIdx >= lastVisible) n else liveIdx)
            }

            val bw = ceil(pw * s).toInt()
            val bh = ceil(ph * s).toInt()
            val usable = k > 0 && bw > 0 && bh > 0 && bw <= MAX_SIDE && bh <= MAX_SIDE &&
                bw.toLong() * bh <= MAX_PIXELS && ensure(k, s, bw, bh, pw, ph, layers)

            val sc = canvas.save()
            if (usable) {
                canvas.clipRect(0f, 0f, pw * s, ph * s)
                canvas.drawBitmap(bmp!!, 0f, 0f, bmpPaint)
                canvas.scale(s, s)
                if (live != null && liveIdx in 0 until k) {
                    val l = layers[liveIdx]
                    val alpha = (l.opacity * 255f).roundToInt().coerceIn(0, 255)
                    val sv = if (alpha < 255) canvas.saveLayerAlpha(0f, 0f, pw.toFloat(), ph.toFloat(), alpha) else -1
                    val sk = scratchTl.get()!!
                    drawStroke(canvas, sk.paint, sk.path, live, Float.MAX_VALUE)
                    if (sv >= 0) canvas.restoreToCount(sv)
                }
                drawLayerRange(canvas, pw, ph, layers, k, n, allowances, live, liveLayerId)
            } else {
                canvas.scale(s, s)
                canvas.clipRect(0f, 0f, pw.toFloat(), ph.toFloat())
                drawLayerRange(canvas, pw, ph, layers, 0, n, allowances, live, liveLayerId)
            }
            canvas.restoreToCount(sc)
        }

        private fun fillSig(k: Int, pw: Int, ph: Int, layers: List<Layer>) {
            val need = HEADER + REC * k
            if (tmp.size < need) tmp = LongArray(need + REC * 4)
            tmp[0] = pw.toLong(); tmp[1] = ph.toLong(); tmp[2] = k.toLong()
            for (i in 0 until k) {
                val l = layers[i]
                val st = l.strokes
                val o = HEADER + REC * i
                val sz = st.size
                tmp[o] = l.id
                tmp[o + 1] = sz.toLong()
                tmp[o + 2] = (if (l.visible) 1L shl 32 else 0L) or (l.opacity.toBits().toLong() and 0xFFFFFFFFL)
                tmp[o + 3] = if (sz > 0) System.identityHashCode(st[0]).toLong() else 0L
                tmp[o + 4] = if (sz > 0) System.identityHashCode(st[sz - 1]).toLong() else 0L
            }
        }

        private fun ensure(k: Int, s: Float, bw: Int, bh: Int, pw: Int, ph: Int, layers: List<Layer>): Boolean {
            fillSig(k, pw, ph, layers)
            val need = HEADER + REC * k
            val scaleOk = bmp != null && cachedK == k && abs(s - cachedS) <= cachedS * 0.002f
            if (scaleOk) {
                var same = true
                var i = 0
                while (i < need) { if (tmp[i] != sig[i]) { same = false; break }; i++ }
                if (same) return true
                if (appendOnly(k, s, layers, need)) { swapSig(); return true }
            }
            // Full rebuild.
            val b = ensureBitmap(bw, bh) ?: return false
            b.eraseColor(0)
            bmpCanvas.setBitmap(b)
            bmpCanvas.save()
            bmpCanvas.scale(s, s)
            drawLayerRange(bmpCanvas, pw, ph, layers, 0, k, null, null, -1L)
            bmpCanvas.restore()
            cachedS = s
            cachedK = k
            swapSig()
            return true
        }

        /** True if only strokes were appended to layer k-1 (opaque, visible); draws them into the bitmap. */
        private fun appendOnly(k: Int, s: Float, layers: List<Layer>, need: Int): Boolean {
            val top = HEADER + REC * (k - 1)
            for (i in 0 until top) if (tmp[i] != sig[i]) return false
            if (tmp[top] != sig[top] || tmp[top + 2] != sig[top + 2] || tmp[top + 3] != sig[top + 3]) return false
            val l = layers[k - 1]
            val oldSize = sig[top + 1].toInt()
            val st = l.strokes
            if (!l.visible || (l.opacity * 255f).roundToInt() < 255 || oldSize <= 0 || st.size <= oldSize) return false
            if (System.identityHashCode(st[oldSize - 1]).toLong() != sig[top + 4]) return false
            val sc = scratchTl.get()!!
            sc.paint.style = Paint.Style.STROKE
            bmpCanvas.setBitmap(bmp)
            bmpCanvas.save()
            bmpCanvas.scale(s, s)
            for (i in oldSize until st.size) drawStroke(bmpCanvas, sc.paint, sc.path, st[i], Float.MAX_VALUE)
            bmpCanvas.restore()
            return true
        }

        private fun swapSig() { val t = sig; sig = tmp; tmp = t }

        private fun ensureBitmap(w: Int, h: Int): Bitmap? {
            var b = bmp
            if (b != null && !b.isRecycled && b.allocationByteCount >= w * h * 4) {
                b.reconfigure(w, h, Bitmap.Config.ARGB_8888)
                return b
            }
            b?.recycle()
            b = try {
                Bitmap.createBitmap((w * 1.15f).toInt() + 1, (h * 1.15f).toInt() + 1, Bitmap.Config.ARGB_8888)
                    .also { it.reconfigure(w, h, Bitmap.Config.ARGB_8888) }
            } catch (_: OutOfMemoryError) {
                bmp = null; cachedK = -1
                return null
            }
            bmp = b
            return b
        }

        private companion object {
            const val HEADER = 3
            const val REC = 5
            const val MAX_SIDE = 4096
            const val MAX_PIXELS = 10_000_000L
        }
    }

    /** Same timeline as AnimationController.allowances (shared [LayerTimeline] cache), as a pure function of [progress]. */
    fun allowancesFor(layers: List<Layer>, progress: Float): Map<Long, Float> =
        com.smitnk.sketcher.anim.LayerTimeline.allowances(layers, progress)

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
