package com.smitnk.sketcher.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.smitnk.sketcher.look.LookSettings
import com.smitnk.sketcher.look.PaperRender
import com.smitnk.sketcher.look.PostFilter

/**
 * Paints one opaque video / GIF frame of [w] x [h] pixels: white, paper, the drawing letterboxed at (dx,dy) scale s,
 * then the post filter. With a post filter the frame is composed in a scratch bitmap (a surface canvas cannot be read
 * back) and the filtered result is drawn onto [target]. Call [release] when done.
 */
class LookFrame(private val w: Int, private val h: Int, private val look: LookSettings) {
    private var scratch: Bitmap? = null

    fun draw(target: Canvas, dx: Float, dy: Float, s: Float, width: Int, height: Int, content: (Canvas) -> Unit) {
        if (look.post == PostFilter.NONE) {
            compose(target, dx, dy, s, width, height, content)
            return
        }
        val b = scratch ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { scratch = it }
        compose(Canvas(b), dx, dy, s, width, height, content)
        PaperRender.applyPost(b, look)
        target.drawBitmap(b, 0f, 0f, null)
    }

    private fun compose(c: Canvas, dx: Float, dy: Float, s: Float, width: Int, height: Int, content: (Canvas) -> Unit) {
        c.drawColor(Color.WHITE)
        PaperRender.drawPaper(c, w, h, look)
        c.save()
        c.translate(dx, dy)
        c.scale(s, s)
        c.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        content(c)
        c.restore()
    }

    fun release() {
        scratch?.recycle(); scratch = null
    }
}
