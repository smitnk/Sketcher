package com.smitnk.sketcher.image.pipeline

import com.smitnk.sketcher.image.ColorStrokeGenerator
import com.smitnk.sketcher.image.GeneratedDrawing
import com.smitnk.sketcher.image.GeneratorSettings
import com.smitnk.sketcher.image.Stroke
import com.smitnk.sketcher.image.StrokeGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Orchestrates line source selection, vectorising, shading and colour fill. */
object SketchPipeline {

    suspend fun run(
        argb: IntArray,
        w: Int,
        h: Int,
        settings: PipelineSettings,
        model: LineArtModel?,
        progress: (stage: String, fraction: Float) -> Unit = { _, _ -> },
    ): GeneratedDrawing = withContext(Dispatchers.Default) {
        val ctx = currentCoroutineContext()
        fun check() = ctx.ensureActive()

        progress("Preparing image", 0.02f)
        val (px, pw, ph) = downscale(argb, w, h, settings.workingSize)
        check()

        // choose line source
        var source = settings.lineSource
        if (source == LineSource.AUTO) {
            progress("Analysing image", 0.06f)
            source = when {
                CartoonLineExtractor.cartoonScore(px, pw, ph) >= 0.6f -> LineSource.CARTOON
                model != null -> LineSource.AI
                else -> LineSource.CLASSIC
            }
        } else if (source == LineSource.AI && model == null) {
            source = LineSource.CLASSIC
        }
        check()

        var ink: FloatArray? = null
        var outline: List<Stroke>? = null
        if (source == LineSource.AI) {
            progress("Running AI line-art model", 0.10f)
            try {
                ink = model!!.infer(px, pw, ph)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                source = LineSource.CLASSIC
                ink = null
            }
            check()
        }
        if (source == LineSource.CARTOON) {
            progress("Extracting cartoon lines", 0.10f)
            ink = CartoonLineExtractor.inkMap(px, pw, ph, settings.detail)
            check()
        }

        if (source == LineSource.CLASSIC) {
            progress("Tracing outline", 0.35f)
            val gs = GeneratorSettings(
                detail = settings.detail,
                includeShading = false,
                maxSide = max(pw, ph),
            )
            outline = StrokeGenerator.generateBlocking(px, pw, ph, gs).outline
        } else {
            progress("Vectorising lines", 0.40f)
            outline = LineArtVectorizer.vectorize(ink!!, pw, ph, settings.detail, settings.lineWidth, settings.maxStrokes)
        }
        check()

        var shading: List<Stroke> = emptyList()
        if (settings.shading != ShadingLevel.NONE) {
            progress("Shading", 0.60f)
            shading = ShadingGenerator.generate(px, pw, ph, settings, ink)
            check()
        }

        var fill: List<Stroke> = emptyList()
        if (settings.colourFill) {
            progress("Colour fill", 0.80f)
            fill = ColorStrokeGenerator.generateFill(
                px, pw, ph, pw, ph, settings.fillColors.coerceIn(6, 16), settings.fillSpacing,
            )
            check()
            outline = ColorStrokeGenerator.colorizeOutline(outline, px, pw, ph, pw, ph)
            check()
        }

        progress("Done", 1f)
        GeneratedDrawing(pw, ph, outline, shading, fill)
    }

    private data class Img(val px: IntArray, val w: Int, val h: Int)

    /** Bilinear / area-ish downscale so the long side is at most [maxSide]; returns input if already small. */
    private fun downscale(src: IntArray, w: Int, h: Int, maxSide: Int): Img {
        val long = max(w, h)
        if (long <= maxSide || maxSide <= 0) return Img(src, w, h)
        val f = maxSide.toFloat() / long
        val tw = max(1, (w * f).roundToInt())
        val th = max(1, (h * f).roundToInt())
        val out = IntArray(tw * th)
        val sx = w.toFloat() / tw
        val sy = h.toFloat() / th
        for (y in 0 until th) {
            val y0 = min(h - 1, floor(y * sy).toInt())
            val y1 = max(y0 + 1, min(h, floor((y + 1) * sy).toInt()))
            for (x in 0 until tw) {
                val x0 = min(w - 1, floor(x * sx).toInt())
                val x1 = max(x0 + 1, min(w, floor((x + 1) * sx).toInt()))
                var a = 0; var r = 0; var g = 0; var b = 0
                for (yy in y0 until y1) {
                    val row = yy * w
                    for (xx in x0 until x1) {
                        val p = src[row + xx]
                        a += p ushr 24; r += (p shr 16) and 255; g += (p shr 8) and 255; b += p and 255
                    }
                }
                val n = (y1 - y0) * (x1 - x0)
                out[y * tw + x] = ((a / n) shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
        return Img(out, tw, th)
    }
}
