package com.smitnk.sketcher.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.smitnk.sketcher.model.Layer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class ExportFormat(val mime: String, val extension: String) {
    PNG("image/png", "png"),
    JPEG("image/jpeg", "jpg"),
}

object Exporter {

    /** [scale] value meaning "size of the imported photo" (see [targetSize]). */
    const val SCALE_ORIGINAL = 0

    /** Hard cap on output pixels (ARGB_8888 = 4 bytes each) so x4 / original never OOMs a phone. */
    const val MAX_PIXELS = 12_000_000L

    /**
     * Output size for [scale] (1, 2, 4 or [SCALE_ORIGINAL]). "Original" uses the imported photo size
     * ([originalWidth] x [originalHeight]) when it is larger than the working size. The result keeps the
     * working aspect ratio and is reduced (never enlarged) to fit [MAX_PIXELS].
     */
    fun targetSize(width: Int, height: Int, scale: Int, originalWidth: Int = 0, originalHeight: Int = 0): Pair<Int, Int> {
        val w = max(1, width)
        val h = max(1, height)
        var f = if (scale == SCALE_ORIGINAL) {
            if (originalWidth > 0 && originalHeight > 0) max(originalWidth.toFloat() / w, originalHeight.toFloat() / h) else 1f
        } else {
            scale.coerceAtLeast(1).toFloat()
        }
        f = max(1f, f)
        val px = w.toDouble() * h * f * f
        if (px > MAX_PIXELS) f = max(1f, sqrt(MAX_PIXELS / (w.toDouble() * h)).toFloat())
        return max(1, (w * f).roundToInt()) to max(1, (h * f).roundToInt())
    }

    /**
     * Renders [layers] (a snapshot taken on the main thread) to a bitmap at [scale] (1/2/4/[SCALE_ORIGINAL]).
     * With [ignoreLayerState], hidden layers are drawn and opacity ignored. The reference photo is never included.
     *
     * Quality: vector strokes are drawn straight at the output size (anti-aliased, true resolution). Pencil-reveal
     * ink textures only exist at working resolution, so they are re-sampled once (bilinear) to the output size
     * and, for [transparent] output, their alpha is re-thresholded ([InkAlpha]) so lines are crisp and solid
     * instead of a blurred, faint upscale. TODO(pipeline): keeping the ink raster at source resolution would
     * give truly sharper x4/Original ink (SketchPipeline.visibilityToArgb currently produces working size).
     */
    fun render(
        width: Int,
        height: Int,
        layers: List<Layer>,
        scale: Int,
        transparent: Boolean,
        ignoreLayerState: Boolean = false,
        originalWidth: Int = 0,
        originalHeight: Int = 0,
    ): Bitmap {
        val (outW, outH) = targetSize(width, height, scale, originalWidth, originalHeight)
        val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        bmp.setHasAlpha(transparent)
        val baked = ArrayList<Bitmap>()
        try {
            val canvas = Canvas(bmp)
            if (!transparent) canvas.drawColor(Color.WHITE)
            canvas.scale(outW.toFloat() / width, outH.toFloat() / height)
            val prepared = layers.map { l ->
                val tex = l.inkTexture?.takeIf { !it.isRecycled } ?: return@map l
                if (tex.width == outW && tex.height == outH && !transparent) return@map l
                val b = bakeInk(tex, outW, outH, solid = transparent)
                baked.add(b)
                withInk(l, b)
            }
            Renderer.drawLayers(
                canvas = canvas,
                width = width,
                height = height,
                layers = prepared,
                ignoreLayerState = ignoreLayerState,
            )
        } finally {
            for (b in baked) b.recycle()
        }
        return bmp
    }

    private fun withInk(src: Layer, ink: Bitmap): Layer = Layer(src.id, src.name).also {
        it.visible = src.visible
        it.opacity = src.opacity
        it.inAnimation = src.inAnimation
        it.animPriority = src.animPriority
        it.locked = src.locked
        it.kind = src.kind
        it.strokes.addAll(src.strokes.toList())
        it.revealWidthPx = src.revealWidthPx
        it.inkTexture = ink
    }

    /**
     * Ink texture (black RGB, alpha = ink) re-sampled to [outW] x [outH]. Only the alpha channel is
     * interpolated (RGB is always 0, so there is no grey fringe), then optionally mapped through [InkAlpha].
     */
    internal fun bakeInk(src: Bitmap, outW: Int, outH: Int, solid: Boolean): Bitmap {
        var b = if (src.width == outW && src.height == outH) {
            src.copy(Bitmap.Config.ARGB_8888, true)
        } else {
            Bitmap.createScaledBitmap(src, outW, outH, true)
        }
        if (!b.isMutable || b.config != Bitmap.Config.ARGB_8888) {
            val m = b.copy(Bitmap.Config.ARGB_8888, true)
            if (b !== src) b.recycle()
            b = m
        }
        val band = 128
        val row = IntArray(outW * band)
        var y = 0
        while (y < outH) {
            val n = min(band, outH - y)
            b.getPixels(row, 0, outW, 0, y, outW, n)
            for (i in 0 until outW * n) {
                val a = row[i] ushr 24
                row[i] = (if (solid) InkAlpha.LUT[a] else a) shl 24 // pure black RGB, alpha only
            }
            b.setPixels(row, 0, outW, 0, y, outW, n)
            y += n
        }
        return b
    }

    /**
     * Saves to Pictures/Sketcher via a pending file that is committed only on success. Returns a human
     * readable location, or null on failure. [checkCancel] may throw CancellationException: the pending file is
     * then deleted, so a cancelled export never leaves a partial file behind.
     */
    fun save(
        context: Context,
        bitmap: Bitmap,
        format: ExportFormat,
        baseName: String,
        checkCancel: () -> Unit = {},
        fileName: String = exportFileName(baseName, format.extension),
        subDir: String? = null,
    ): String? {
        val compressFormat = if (format == ExportFormat.PNG) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        val quality = if (format == ExportFormat.PNG) 100 else 95
        val target = OutputTarget.create(context, false, fileName, format.mime, subDir) ?: return null
        var ok = false
        try {
            checkCancel()
            val written = target.openStream()?.use { bitmap.compress(compressFormat, quality, it) } ?: false
            checkCancel()
            ok = written
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: Exception) {
            ok = false
        } finally {
            if (ok) target.commit() else target.abort()
        }
        return if (ok) target.displayPath else null
    }
}
