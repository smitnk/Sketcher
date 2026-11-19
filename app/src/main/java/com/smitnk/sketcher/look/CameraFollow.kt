package com.smitnk.sketcher.look

import kotlin.math.min

/**
 * Camera as "scale by [zoom], then translate by ([tx],[ty])" in sheet (image-pixel) units: image point p is shown at
 * p * zoom + t. Identity = the whole sheet. Pure data so it can be unit-tested; see `applyTo` in PenOverlay.kt.
 */
data class CameraXform(val zoom: Float, val tx: Float, val ty: Float) {
    val isIdentity: Boolean get() = zoom == 1f && tx == 0f && ty == 0f

    /** Top-left of the visible window in image px. */
    val originX: Float get() = -tx / zoom
    val originY: Float get() = -ty / zoom

    companion object {
        val IDENTITY = CameraXform(1f, 0f, 0f)
    }
}

/**
 * Whiteboard / time-lapse follow camera. Keeps the (smoothed) pen tip near the centre of a zoomed window, clamped to
 * the sheet, eases in from the full view at the start and back out over the last [EASE_OUT_SEC], so the final frame
 * (progress 1) is exactly the whole drawing. Pure function of its arguments: scrubbing and export agree.
 */
object CameraFollow {
    const val ZOOM_MIN = 1f
    const val ZOOM_MAX = 2.5f
    const val EASE_IN_SEC = 1.0f
    const val EASE_OUT_SEC = 1.5f

    /** 0 = full view, 1 = full zoom, as a function of time. Short animations scale both ramps down. */
    fun ease(progress: Float, durationSec: Float): Float {
        val dur = durationSec.coerceAtLeast(0.5f)
        val p = progress.coerceIn(0f, 1f)
        if (p >= 1f || p <= 0f) return 0f
        val t = p * dur
        val tin = min(EASE_IN_SEC, dur * 0.25f)
        val tout = min(EASE_OUT_SEC, dur * 0.35f)
        return smooth(t / tin) * smooth((dur - t) / tout)
    }

    private fun smooth(x: Float): Float {
        val u = x.coerceIn(0f, 1f)
        return u * u * (3f - 2f * u)
    }

    /**
     * @param zoom full zoom (clamped 1..2.5), @param strength 0..1: how far the window centre moves from the sheet
     * centre towards the pen (1 = pen centred where the sheet allows).
     */
    fun transform(
        track: TipTrack?,
        progress: Float,
        durationSec: Float,
        width: Float,
        height: Float,
        zoom: Float,
        strength: Float = 1f,
    ): CameraXform {
        val k = ease(progress, durationSec)
        if (track == null || k <= 0f || width <= 0f || height <= 0f) return CameraXform.IDENTITY
        val z = 1f + (zoom.coerceIn(ZOOM_MIN, ZOOM_MAX) - 1f) * k
        if (z <= 1f) return CameraXform.IDENTITY
        val st = strength.coerceIn(0f, 1f)
        val fx = width / 2f + (track.camX(progress) - width / 2f) * st
        val fy = height / 2f + (track.camY(progress) - height / 2f) * st
        val vw = width / z
        val vh = height / z
        val ox = (fx - vw / 2f).coerceIn(0f, width - vw)
        val oy = (fy - vh / 2f).coerceIn(0f, height - vh)
        return CameraXform(z, -ox * z, -oy * z)
    }
}
