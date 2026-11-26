package com.smitnk.sketcher.ui.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.smitnk.sketcher.export.Renderer
import com.smitnk.sketcher.look.LookSettings
import com.smitnk.sketcher.look.PaperRender
import com.smitnk.sketcher.look.PostFilter
import com.smitnk.sketcher.model.Layer
import kotlin.math.ceil
import kotlin.math.max

/**
 * Screen preview of the 1-bit / halftone post filter. Costly, so it is only used while nothing is being drawn or
 * played, and it renders the finished drawing at reduced resolution ([MAX_SIDE] px long side), re-rendering only
 * when a cheap content signature changes. UI thread only; call [release] when the editor goes away.
 */
class PostPreviewCache {
    private var bmp: Bitmap? = null
    private var sig = Long.MIN_VALUE
    private val nearest = Paint() // no bitmap filtering: keep the pixels crisp
    private val dst = android.graphics.RectF()

    fun release() {
        bmp?.recycle(); bmp = null; sig = Long.MIN_VALUE
    }

    /** Draws the filtered sheet at device scale [s] (origin already translated). False if it could not be prepared. */
    fun draw(c: Canvas, s: Float, pw: Int, ph: Int, layers: List<Layer>, look: LookSettings): Boolean {
        if (look.post == PostFilter.NONE || pw <= 0 || ph <= 0) return false
        val r = minOf(1f, com.smitnk.sketcher.ui.settings.SpeedQuality.previewMaxSide.toFloat() / max(pw, ph))
        val bw = ceil(pw * r).toInt().coerceAtLeast(1)
        val bh = ceil(ph * r).toInt().coerceAtLeast(1)
        val key = signature(layers, look, bw, bh)
        var b = bmp
        if (b == null || b.isRecycled || b.width != bw || b.height != bh || key != sig) {
            if (b == null || b.isRecycled || b.width != bw || b.height != bh) {
                b?.recycle()
                b = try { Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888) } catch (_: OutOfMemoryError) { bmp = null; return false }
                bmp = b
            }
            val bc = Canvas(b)
            bc.drawColor(Color.WHITE)
            PaperRender.drawPaper(bc, bw, bh, look)
            bc.save()
            bc.scale(bw.toFloat() / pw, bh.toFloat() / ph)
            Renderer.drawLayers(bc, pw, ph, layers, look = look)
            bc.restore()
            PaperRender.applyPost(b, look)
            sig = key
        }
        dst.set(0f, 0f, pw * s, ph * s)
        c.drawBitmap(b, null, dst, nearest)
        return true
    }

    private fun signature(layers: List<Layer>, look: LookSettings, bw: Int, bh: Int): Long {
        var h = look.hashCode().toLong() * 31 + bw * 7919L + bh
        for (l in layers) {
            h = h * 31 + l.id
            h = h * 31 + (if (l.visible) 1 else 0)
            h = h * 31 + l.opacity.toBits()
            h = h * 31 + l.kind.ordinal
            val st = l.strokes
            h = h * 31 + st.size
            if (st.isNotEmpty()) {
                h = h * 31 + System.identityHashCode(st[0])
                h = h * 31 + System.identityHashCode(st[st.size - 1])
            }
            val tex = l.inkTexture
            if (tex != null) h = h * 31 + System.identityHashCode(tex) * 1_000_003L + l.inkRev
        }
        return h
    }

    private companion object {
        const val MAX_SIDE = 720
    }
}
