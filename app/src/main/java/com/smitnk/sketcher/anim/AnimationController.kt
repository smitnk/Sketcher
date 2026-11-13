package com.smitnk.sketcher.anim

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.smitnk.sketcher.model.Layer

/**
 * Reveals strokes progressively, layer by layer in animPriority order, then list order (index 0 first).
 * Progress is distributed over total stroke length, so long strokes take longer to draw.
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
    var durationSec by mutableFloatStateOf(12f)

    fun togglePlay() {
        if (playing) {
            playing = false
        } else {
            if (!active || progress >= 1f) progress = 0f
            active = true
            playing = true
        }
    }

    fun restart() {
        progress = 0f
        active = true
        playing = true
    }

    /** Scrub to [fraction] (0..1): pauses playback and shows the partial drawing at that progress. */
    fun seek(fraction: Float) {
        playing = false
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
        progress += deltaSeconds / durationSec.coerceAtLeast(0.5f)
        if (progress >= 1f) {
            progress = 1f
            playing = false
        }
    }

    /**
     * How much stroke length may be shown per layer id, or null when the full drawing is shown.
     * Layers that are hidden or excluded from the animation have no entry (= drawn fully).
     */
    fun allowances(layers: List<Layer>): Map<Long, Float>? {
        if (!active) return null
        var total = 0f
        for (l in layers) if (l.visible && l.inAnimation) total += l.totalLength()
        var budget = progress * total
        val map = HashMap<Long, Float>()
        for (l in layers.sortedBy { it.animPriority }) { // stable: ties keep list order
            if (!l.visible || !l.inAnimation) continue
            val len = l.totalLength()
            map[l.id] = budget.coerceIn(0f, len)
            budget -= len
        }
        return map
    }
}
