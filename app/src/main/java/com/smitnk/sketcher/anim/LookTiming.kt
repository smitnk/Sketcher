package com.smitnk.sketcher.anim

import com.smitnk.sketcher.look.LookSettings
import com.smitnk.sketcher.look.StrokeFeel
import kotlin.math.floor

/** Pure helpers tying a [LookSettings] to animation time (shared by screen playback and every exporter). */
object LookTiming {
    const val DRAWINGS_PER_SEC = 12.0

    /**
     * Progress as the viewer sees it: with onTwos it is held for 1/12 s (a new drawing every 12th of a second).
     * Progress 1 stays exactly 1 so the finished drawing is never cut short.
     */
    fun effectiveProgress(progress: Float, durationSec: Float, look: LookSettings): Float {
        val p = progress.coerceIn(0f, 1f)
        if (!look.onTwos || p >= 1f || p <= 0f) return p
        val n = (durationSec.coerceAtLeast(0.5f) * DRAWINGS_PER_SEC)
        return (floor(p * n) / n).toFloat()
    }

    /** Index of the 1/12 s drawing at [progress]; the scratch-film boil re-seeds with it. 0 when not boiling. */
    fun drawingIndex(progress: Float, durationSec: Float, look: LookSettings): Int {
        if (look.feel != StrokeFeel.SCRATCH_BOIL) return 0
        val p = progress.coerceIn(0f, 1f)
        return floor(p.toDouble() * durationSec.coerceAtLeast(0.5f) * DRAWINGS_PER_SEC).toInt()
    }
}
