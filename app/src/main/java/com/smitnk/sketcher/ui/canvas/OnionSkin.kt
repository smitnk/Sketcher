package com.smitnk.sketcher.ui.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.smitnk.sketcher.export.Renderer
import com.smitnk.sketcher.model.Frame
import com.smitnk.sketcher.model.contentSignature
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Screen-only onion skin: the previous and next frame, each flattened to one tint, cached as one bitmap so a
 * live stroke does not re-render two whole drawings every display frame. Rebuilt when either neighbour changes or
 * the zoom drifts by more than ~40%. UI thread only; call [release] when the canvas goes away.
 */
class OnionCache {
    private var bmp: Bitmap? = null
    private val bmpCanvas = Canvas()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    private var sig = 0L
    private var renderScale = 0f

    fun release() {
        bmp?.recycle()
        bmp = null
        sig = 0L
    }

    /**
     * [canvas] is translated so (0,0) is the sheet's top-left; [s] is device pixels per image pixel.
     * Alphas are 0..255; a null frame is skipped.
     */
    fun draw(
        canvas: Canvas,
        s: Float,
        pw: Int,
        ph: Int,
        prev: Frame?,
        next: Frame?,
        prevTint: Int,
        nextTint: Int,
        prevAlpha: Int,
        nextAlpha: Int,
    ) {
        if ((prev == null || prevAlpha <= 0) && (next == null || nextAlpha <= 0)) return
        val want = minOf(s, 2f, sqrt(MAX_PIXELS / (pw.toDouble() * ph)).toFloat()).coerceAtLeast(0.05f)
        var key = 17L
        key = key * 31 + (prev?.contentSignature() ?: 0L)
        key = key * 31 + (next?.contentSignature() ?: 0L)
        key = key * 31 + prevTint + prevAlpha * 7L
        key = key * 31 + nextTint + nextAlpha * 13L
        key = key * 31 + pw * 4099L + ph
        var b = bmp
        val scaleOk = b != null && !b.isRecycled && renderScale > 0f && want / renderScale in 0.7f..1.4f
        if (!scaleOk || key != sig) {
            val bw = ceil(pw * want).toInt().coerceAtLeast(1)
            val bh = ceil(ph * want).toInt().coerceAtLeast(1)
            b = ensure(bw, bh) ?: return
            b.eraseColor(0)
            bmpCanvas.setBitmap(b)
            bmpCanvas.save()
            bmpCanvas.scale(bw.toFloat() / pw, bh.toFloat() / ph)
            if (prev != null) Renderer.drawOnion(bmpCanvas, pw, ph, prev.layers, prevTint, prevAlpha)
            if (next != null) Renderer.drawOnion(bmpCanvas, pw, ph, next.layers, nextTint, nextAlpha)
            bmpCanvas.restore()
            bmpCanvas.setBitmap(null)
            sig = key
            renderScale = want
        }
        dst.set(0f, 0f, pw * s, ph * s)
        canvas.drawBitmap(b!!, null, dst, paint)
    }

    private fun ensure(w: Int, h: Int): Bitmap? {
        val cur = bmp
        if (cur != null && !cur.isRecycled && cur.width == w && cur.height == h) return cur
        cur?.recycle()
        return try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp = it }
        } catch (_: OutOfMemoryError) {
            bmp = null
            null
        }
    }

    private companion object {
        const val MAX_PIXELS = 6_000_000.0
    }
}
