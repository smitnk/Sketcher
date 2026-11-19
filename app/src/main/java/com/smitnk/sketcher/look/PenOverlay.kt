package com.smitnk.sketcher.look

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.smitnk.sketcher.model.Layer
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Concatenates the camera onto [c] (image-pixel units). */
fun CameraXform.applyTo(c: Canvas) {
    if (isIdentity) return
    c.translate(tx, ty)
    c.scale(zoom, zoom)
}

/**
 * Per-frame motion state (pen + camera) shared by the on-screen player and every exporter, so both draw the same thing
 * for the same progress. Build with [MotionFx.prepare]; null means "nothing to do" (feature off, or finished frame).
 */
class Motion internal constructor(
    val track: TipTrack,
    /** Drawn progress (onTwos-quantised). */
    val progress: Float,
    val camera: CameraXform,
    private val look: LookSettings,
    private val w: Float,
    private val h: Float,
) {
    /** Draws the pen sprite; [c] must already be in image-pixel units (inside the clip and the camera transform). */
    fun drawPen(c: Canvas, layers: List<Layer>) {
        if (!look.showPen) return
        val a = PenOverlay.alpha(progress, track.durationSec)
        if (a <= 0f) return
        val tip = track.tipAt(progress)
        if (tip.layerIndex < 0) return
        val stroke = layers.getOrNull(tip.layerIndex)?.strokes?.getOrNull(tip.strokeIndex)
        val ink = stroke?.color ?: 0xFF22262B.toInt()
        PenOverlay.draw(
            c, tip.x, tip.y, track.headingX(progress), track.headingY(progress), track.lift(progress),
            ink or (0xFF shl 24), look.penStyle, PenOverlay.UNIT_FRACTION * min(w, h), a,
        )
    }
}

object MotionFx {
    fun prepare(layers: List<Layer>, look: LookSettings, progress: Float, durationSec: Float, w: Int, h: Int): Motion? {
        if (!look.showPen && !look.cameraFollow) return null
        if (w <= 0 || h <= 0) return null
        val p = TipTrack.effective(progress, durationSec, look)
        if (p >= 1f) return null // finished frame: full view, no pen
        val track = TipTrack.forLayers(layers, look, durationSec)
        val cam = if (look.cameraFollow) CameraFollow.transform(track, p, durationSec, w.toFloat(), h.toFloat(), look.cameraZoom)
        else CameraXform.IDENTITY
        return Motion(track, p, cam, look, w.toFloat(), h.toFloat())
    }
}

/**
 * Procedural pen sprite: a stylised pencil / marker / brush whose TIP is at the given point, with a drop shadow on the
 * paper. The body points away from the direction of travel (blended with a natural up-right grip); when the pen is
 * lifted the body floats up and its shadow shrinks and fades.
 */
object PenOverlay {
    /** Sprite length as a fraction of the sheet's short side. */
    const val UNIT_FRACTION = 0.16f
    private const val FADE_IN_SEC = 0.3f
    private const val FADE_OUT_SEC = 0.5f

    /** Visibility 0..1: fades in at the start, out over the last [FADE_OUT_SEC]; 0 on the finished frame. */
    fun alpha(progress: Float, durationSec: Float): Float {
        val dur = durationSec.coerceAtLeast(0.5f)
        val p = progress.coerceIn(0f, 1f)
        if (p >= 1f) return 0f
        val t = p * dur
        val fi = min(1f, t / min(FADE_IN_SEC, dur * 0.2f))
        val fo = min(1f, (dur - t) / min(FADE_OUT_SEC, dur * 0.2f))
        return max(0f, min(fi, fo))
    }

    private class Scratch {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        val path = Path()
        val rect = RectF()
    }

    private val scratch = object : ThreadLocal<Scratch>() {
        override fun initialValue() = Scratch()
    }

    private const val OUTLINE = 0x8C3A3F47.toInt()

    /**
     * @param hx,hy unit direction of travel ((0,0) = unknown), @param lift 0 on paper .. 1 lifted,
     * @param ink colour of the lead / nib / bristles, @param len sprite length (image px), @param alpha 0..1.
     */
    fun draw(c: Canvas, x: Float, y: Float, hx: Float, hy: Float, lift: Float, ink: Int, style: PenStyle, len: Float, alpha: Float) {
        if (alpha <= 0f || len <= 0f) return
        val sc = scratch.get()!!
        val lf = lift.coerceIn(0f, 1f)
        // Back-of-pen direction: opposite to travel, blended with a natural grip pointing up-right.
        var dx = 0.45f * 0.6f - 0.55f * hx
        var dy = 0.45f * -0.8f - 0.55f * hy
        val dl = hypot(dx, dy)
        if (dl < 1e-3f) { dx = 0.6f; dy = -0.8f } else { dx /= dl; dy /= dl }
        val angle = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()

        val save = if (alpha < 1f) c.saveLayerAlpha(x - len * 1.6f, y - len * 1.6f, x + len * 1.6f, y + len * 1.6f, (alpha * 255f).roundToInt()) else c.save()
        sc.line.strokeWidth = max(0.5f, len * 0.008f)
        // Shadow on the paper: offset away from the light, smaller + fainter when the pen is lifted.
        run {
            val off = len * (0.02f + 0.05f * lf)
            val k = 1f - 0.35f * lf
            val a = (0.24f * (1f - 0.45f * lf) * 255f).roundToInt()
            c.save()
            c.translate(x + off, y + off * 1.4f)
            c.rotate(angle)
            c.scale(k, k)
            for ((grow, fa) in listOf(0.012f to 0.45f, 0.006f to 0.55f, 0f to 1f)) {
                sc.fill.color = 0
                sc.fill.alpha = (a * fa / 1.5f).roundToInt().coerceIn(0, 255)
                silhouette(sc.path, style, len)
                sc.line.color = 0; sc.line.alpha = sc.fill.alpha; sc.line.strokeWidth = len * grow * 2f
                c.drawPath(sc.path, sc.fill)
                if (grow > 0f) c.drawPath(sc.path, sc.line)
            }
            c.restore()
        }
        // The pen itself: lifted pens hover up and slightly back from the paper.
        c.translate(x - len * 0.01f * lf, y - len * 0.07f * lf)
        c.rotate(angle)
        sc.line.strokeWidth = max(0.5f, len * 0.008f)
        when (style) {
            PenStyle.PENCIL -> pencil(c, sc, ink, len)
            PenStyle.MARKER -> marker(c, sc, ink, len)
            PenStyle.BRUSH -> brush(c, sc, ink, len)
        }
        c.restoreToCount(save)
    }

    private fun halfW(style: PenStyle, len: Float) = len * when (style) { PenStyle.PENCIL -> 0.036f; PenStyle.MARKER -> 0.042f; PenStyle.BRUSH -> 0.03f }

    private fun silhouette(p: Path, style: PenStyle, len: Float) {
        val hw = halfW(style, len)
        p.rewind()
        when (style) {
            PenStyle.PENCIL -> { p.moveTo(0f, 0f); p.lineTo(len * 0.14f, -hw); p.lineTo(len, -hw); p.lineTo(len, hw); p.lineTo(len * 0.14f, hw) }
            PenStyle.MARKER -> { p.moveTo(0f, -hw * 0.45f); p.lineTo(len * 0.12f, -hw); p.lineTo(len, -hw); p.lineTo(len, hw); p.lineTo(len * 0.12f, hw); p.lineTo(0f, hw * 0.45f) }
            PenStyle.BRUSH -> { p.moveTo(0f, 0f); p.lineTo(len * 0.14f, -hw); p.lineTo(len * 0.26f, -hw * 0.9f); p.lineTo(len, -hw * 0.5f); p.lineTo(len, hw * 0.5f); p.lineTo(len * 0.26f, hw * 0.9f); p.lineTo(len * 0.14f, hw) }
        }
        p.close()
    }

    private fun fillRect(c: Canvas, sc: Scratch, x0: Float, y0: Float, x1: Float, y1: Float, color: Int) {
        sc.fill.color = color
        sc.rect.set(x0, y0, x1, y1)
        c.drawRect(sc.rect, sc.fill)
    }

    private fun outline(c: Canvas, sc: Scratch) {
        sc.line.color = OUTLINE
        c.drawPath(sc.path, sc.line)
    }

    private fun pencil(c: Canvas, sc: Scratch, ink: Int, len: Float) {
        val hw = halfW(PenStyle.PENCIL, len)
        val cone = len * 0.14f
        val ferrule0 = len * 0.88f
        val eraser0 = len * 0.95f
        // wood cone
        sc.path.rewind(); sc.path.moveTo(0f, 0f); sc.path.lineTo(cone, -hw); sc.path.lineTo(cone, hw); sc.path.close()
        sc.fill.color = 0xFFE9C99B.toInt(); c.drawPath(sc.path, sc.fill)
        // lead in the layer's ink colour
        val lead = cone * 0.32f
        sc.path.rewind(); sc.path.moveTo(0f, 0f); sc.path.lineTo(lead, -hw * lead / cone); sc.path.lineTo(lead, hw * lead / cone); sc.path.close()
        sc.fill.color = ink; c.drawPath(sc.path, sc.fill)
        // body, with a darker centre stripe and a highlight
        fillRect(c, sc, cone, -hw, ferrule0, hw, 0xFFF2B632.toInt())
        fillRect(c, sc, cone, -hw * 0.25f, ferrule0, hw * 0.35f, 0x33B26A00)
        fillRect(c, sc, cone, -hw * 0.85f, ferrule0, -hw * 0.6f, 0x59FFFFFF)
        // ferrule + eraser
        fillRect(c, sc, ferrule0, -hw, eraser0, hw, 0xFFB8BCC4.toInt())
        fillRect(c, sc, ferrule0 + len * 0.02f, -hw, ferrule0 + len * 0.026f, hw, 0x66000000)
        fillRect(c, sc, eraser0, -hw, len, hw, 0xFFE88A8A.toInt())
        silhouette(sc.path, PenStyle.PENCIL, len); outline(c, sc)
    }

    private fun marker(c: Canvas, sc: Scratch, ink: Int, len: Float) {
        val hw = halfW(PenStyle.MARKER, len)
        val nibW = hw * 0.45f
        // chisel nib in ink colour, grey plastic shoulder, white barrel with a colour band, ink-coloured cap
        fillRect(c, sc, 0f, -nibW, len * 0.05f, nibW, ink)
        sc.path.rewind()
        sc.path.moveTo(len * 0.05f, -nibW); sc.path.lineTo(len * 0.12f, -hw); sc.path.lineTo(len * 0.12f, hw); sc.path.lineTo(len * 0.05f, nibW); sc.path.close()
        sc.fill.color = 0xFFD7DBE0.toInt(); c.drawPath(sc.path, sc.fill)
        fillRect(c, sc, len * 0.12f, -hw, len * 0.86f, hw, 0xFFF4F5F7.toInt())
        fillRect(c, sc, len * 0.12f, -hw, len * 0.28f, hw, ink)
        fillRect(c, sc, len * 0.12f, -hw * 0.85f, len * 0.86f, -hw * 0.6f, 0x80FFFFFF.toInt())
        fillRect(c, sc, len * 0.86f, -hw, len, hw, ink)
        silhouette(sc.path, PenStyle.MARKER, len); outline(c, sc)
    }

    private fun brush(c: Canvas, sc: Scratch, ink: Int, len: Float) {
        val hw = halfW(PenStyle.BRUSH, len)
        // bristle tuft
        sc.path.rewind()
        sc.path.moveTo(0f, 0f)
        sc.path.quadTo(len * 0.04f, -hw * 1.15f, len * 0.14f, -hw)
        sc.path.lineTo(len * 0.14f, hw)
        sc.path.quadTo(len * 0.04f, hw * 1.15f, 0f, 0f)
        sc.path.close()
        sc.fill.color = ink; c.drawPath(sc.path, sc.fill)
        // metal ferrule
        fillRect(c, sc, len * 0.14f, -hw, len * 0.26f, hw, 0xFFB8BCC4.toInt())
        fillRect(c, sc, len * 0.19f, -hw, len * 0.2f, hw, 0x66000000)
        // tapered wooden handle
        sc.path.rewind()
        sc.path.moveTo(len * 0.26f, -hw * 0.9f); sc.path.lineTo(len, -hw * 0.5f); sc.path.lineTo(len, hw * 0.5f); sc.path.lineTo(len * 0.26f, hw * 0.9f); sc.path.close()
        sc.fill.color = 0xFF8B5A3C.toInt(); c.drawPath(sc.path, sc.fill)
        fillRect(c, sc, len * 0.26f, -hw * 0.75f, len, -hw * 0.45f, 0x40FFFFFF)
        silhouette(sc.path, PenStyle.BRUSH, len); outline(c, sc)
    }
}
