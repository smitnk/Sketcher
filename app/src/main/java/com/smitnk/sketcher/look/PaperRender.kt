package com.smitnk.sketcher.look

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/** Android glue for [PaperTexture] and [PostFilters]. Thread-safe (small synchronised LRU of paper bitmaps). */
object PaperRender {
    private const val CACHE_MAX_PIXELS = 4_000_000L
    private const val TEX_MAX_PIXELS = 12_000_000L

    private data class Key(val paper: Paper, val w: Int, val h: Int, val seed: Int)

    private val cache = object : LinkedHashMap<Key, Bitmap>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Bitmap>?): Boolean = size > 2
    }
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    private fun texture(look: LookSettings, w: Int, h: Int): Pair<Bitmap, Boolean>? {
        val key = Key(look.paper, w, h, look.seed)
        synchronized(cache) { cache[key]?.takeIf { !it.isRecycled }?.let { return it to true } }
        val px = PaperTexture.generate(look.paper, w, h, look.seed)
        val bmp = try {
            Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        } catch (_: OutOfMemoryError) {
            return null
        }
        if (w.toLong() * h > CACHE_MAX_PIXELS) return bmp to false
        synchronized(cache) { cache[key] = bmp }
        return bmp to true
    }

    /**
     * Draws the paper (colour x tooth) over the rectangle (0,0)-([w],[h]) in the current canvas units. Does nothing for
     * [Paper.PLAIN] (the caller's white / theme paper stays). The texture is generated at [w] x [h], or reduced to
     * ~[TEX_MAX_PIXELS] pixels and stretched.
     */
    fun drawPaper(canvas: Canvas, w: Int, h: Int, look: LookSettings) {
        if (!PaperTexture.hasTexture(look.paper) || w <= 0 || h <= 0) return
        var tw = w
        var th = h
        if (tw.toLong() * th > TEX_MAX_PIXELS) {
            val f = Math.sqrt(TEX_MAX_PIXELS.toDouble() / (tw.toDouble() * th)).toFloat()
            tw = (w * f).toInt().coerceAtLeast(1); th = (h * f).toInt().coerceAtLeast(1)
        }
        val (bmp, cached) = texture(look, tw, th) ?: return
        val r = RectF(0f, 0f, w.toFloat(), h.toFloat())
        synchronized(paint) { canvas.drawBitmap(bmp, null, r, paint) }
        // A hardware canvas records the draw and reads the bitmap later: never recycle under it.
        if (!cached && !canvas.isHardwareAccelerated) bmp.recycle()
    }

    /**
     * Applies [LookSettings.post] to [bmp] in place (mutable ARGB_8888). [transparent]: ink-only output
     * (black ink on transparent). No-op for [PostFilter.NONE].
     */
    fun applyPost(bmp: Bitmap, look: LookSettings, transparent: Boolean = false) {
        if (look.post == PostFilter.NONE || bmp.isRecycled) return
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        if (transparent) PostFilters.applyToInk(px, w, h, look.post, look.seed) else PostFilters.apply(px, w, h, look.post, look.seed)
        bmp.setPixels(px, 0, w, 0, 0, w, h)
    }
}
