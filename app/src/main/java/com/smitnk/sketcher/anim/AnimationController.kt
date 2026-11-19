package com.smitnk.sketcher.anim

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.smitnk.sketcher.look.LookSettings
import com.smitnk.sketcher.model.Layer

/**
 * Reveals strokes progressively, layer by layer in animPriority order, then list order (index 0 first).
 * Progress maps onto a human-like timeline (see [DrawTimeline]): eased strokes, pen-lift pauses, faster shading.
 * The frame loop lives in the UI (withFrameNanos) and calls [advance].
 */
class AnimationController {
    /** True while the animated (partially revealed) view is shown. */
    var active by mutableStateOf(false)
        private set
    var playing by mutableStateOf(false)
        private set
    var progress by mutableFloatStateOf(0f)
        private set

    /** Total animation length in seconds (2..60). */
    var durationSec by mutableFloatStateOf(DEFAULT_DURATION_SEC)

    /** Seconds left of the end hold (final frame shown at progress 1 before [playing] turns off). */
    private var holdLeft = 0f

    fun togglePlay() {
        if (playing) {
            playing = false
        } else {
            if (!active || progress >= 1f) progress = 0f
            holdLeft = 0f
            active = true
            playing = true
        }
    }

    fun restart() {
        progress = 0f
        holdLeft = 0f
        active = true
        playing = true
    }

    /** Scrub to [fraction] (0..1): pauses playback and shows the partial drawing at that progress. */
    fun seek(fraction: Float) {
        playing = false
        holdLeft = 0f
        active = true
        progress = fraction.coerceIn(0f, 1f)
    }

    fun showFinished() {
        playing = false
        active = false
        progress = 1f
    }

    fun advance(deltaSeconds: Float) {
        if (!playing) return
        // A long frame (GC / jank) must not skip a chunk of the drawing: cap what one frame can advance.
        val dt = deltaSeconds.coerceIn(0f, MAX_FRAME_SEC)
        if (progress < 1f) {
            progress += dt / durationSec.coerceAtLeast(0.5f)
            if (progress >= 1f) {
                progress = 1f // the final frame is drawn at exactly 1, then held
                holdLeft = END_HOLD_SEC
            }
        } else {
            holdLeft -= dt
            if (holdLeft <= 0f) {
                playing = false
                com.smitnk.sketcher.ui.DebugDump.logAnimEnd(progress)
            }
        }
    }

    /**
     * How much stroke length may be shown per layer id, or null when the full drawing is shown. Zero allocation per frame
     * on a timeline cache hit: the returned instance is reused (valid until the next call).
     * Layers that are hidden or excluded from the animation have no entry (= drawn fully).
     */
    fun allowances(layers: List<Layer>, look: LookSettings = LookSettings()): Allowances? {
        if (!active) return null
        return LayerTimeline.allowances(layers, progress, look, durationSec, reuse)
    }

    /** Refilled every frame; the render pass reads it before the next frame, so one instance is enough. */
    private val reuse = Allowances()

    /** Drawing index (1/12 s steps) for the scratch-film boil at the current progress; pass to Renderer as `drawing`. */
    fun drawingIndex(look: LookSettings): Int = LookTiming.drawingIndex(progress, durationSec, look)

    companion object {
        /** Default animation length (seconds) of a new project / frame. */
        const val DEFAULT_DURATION_SEC = 20f
        const val END_HOLD_SEC = 0.6f
        const val MAX_FRAME_SEC = 0.1f
    }
}
