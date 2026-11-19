package com.smitnk.sketcher.look

import com.smitnk.sketcher.anim.LayerTimeline
import com.smitnk.sketcher.anim.LookTiming
import com.smitnk.sketcher.model.Layer
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Where the pen tip is at one moment of the draw-along timeline (image pixels). */
data class Tip(
    val x: Float,
    val y: Float,
    /** Index into the project's full layer list (-1 when there is nothing to draw). */
    val layerIndex: Int,
    val strokeIndex: Int,
    val penDown: Boolean,
    /** Image px per second of animation time (filled by [TipTrack]; 0 from a raw source). */
    val speed: Float = 0f,
)

/**
 * Precomputed pen-tip table for the pen overlay and the follow camera. The timeline (0..1) is sampled at [STEPS]
 * evenly spaced progress values once; everything derived (camera path, heading, pen lift) is a pure function of that
 * table, so scrubbing, on-screen playback and MP4/GIF export all see the same numbers for the same progress.
 *
 * - [tipAt]: the pen tip itself, evaluated live from [source] (exact: it sits on the end of the ink).
 * - [camX]/[camY]: the same path smoothed by a NON-CAUSAL Gaussian ([SIGMA_SEC] of timeline) so the camera starts
 *   moving before the pen does and never jerks at stroke boundaries.
 */
class TipTrack private constructor(
    val durationSec: Float,
    private val source: (Float) -> Tip,
    private val camXs: FloatArray,
    private val camYs: FloatArray,
    private val hxs: FloatArray,
    private val hys: FloatArray,
    private val lifts: FloatArray,
    private val speeds: FloatArray,
) {
    val steps: Int get() = camXs.size - 1

    /** Live pen tip at [progress] (0..1), with [Tip.speed] taken from the table. */
    fun tipAt(progress: Float): Tip {
        val t = source(progress.coerceIn(0f, 1f))
        return t.copy(speed = lerp(speeds, progress))
    }

    /** Smoothed camera focus (image px). */
    fun camX(progress: Float): Float = lerp(camXs, progress)
    fun camY(progress: Float): Float = lerp(camYs, progress)

    /** Unit vector the pen moves along (held while the pen rests); (0,0) if the track never moved. */
    fun headingX(progress: Float): Float = lerp(hxs, progress)
    fun headingY(progress: Float): Float = lerp(hys, progress)

    /** 0 = pen on paper, 1 = fully lifted; eased over ~0.1 s so the pen does not pop up and down. */
    fun lift(progress: Float): Float = lerp(lifts, progress)

    private fun lerp(a: FloatArray, progress: Float): Float {
        val f = progress.coerceIn(0f, 1f) * (a.size - 1)
        val i = min(f.toInt(), a.size - 2).coerceAtLeast(0)
        if (a.size < 2) return a[0]
        val u = f - i
        return a[i] + (a[i + 1] - a[i]) * u
    }

    companion object {
        const val STEPS = 600
        const val SIGMA_SEC = 0.4f
        const val HEADING_SIGMA_SEC = 0.12f
        const val LIFT_SIGMA_SEC = 0.06f

        /** Builds the table. [source] must be a pure function of progress. */
        fun build(durationSec: Float, source: (Float) -> Tip, steps: Int = STEPS): TipTrack {
            val dur = durationSec.coerceAtLeast(0.5f)
            val n = steps.coerceAtLeast(2)
            val raw = Array(n + 1) { source(it.toFloat() / n) }
            val xs = FloatArray(n + 1) { raw[it].x }
            val ys = FloatArray(n + 1) { raw[it].y }
            val dt = dur / n
            val sigma = SIGMA_SEC / dt // in samples
            val cx = gaussianSmooth(xs, sigma)
            val cy = gaussianSmooth(ys, sigma)

            val speeds = FloatArray(n + 1) { i ->
                val a = max(0, i - 1); val b = min(n, i + 1)
                if (b == a) 0f else hypot(xs[b] - xs[a], ys[b] - ys[a]) / ((b - a) * dt)
            }
            // Heading: unit tangent over a short window, held across stalls, then lightly smoothed.
            val k = max(1, (0.1f / dt).roundToInt())
            val hx = FloatArray(n + 1)
            val hy = FloatArray(n + 1)
            var lx = 0f; var ly = 0f
            for (i in 0..n) {
                val a = max(0, i - k); val b = min(n, i + k)
                val dx = xs[b] - xs[a]; val dy = ys[b] - ys[a]
                val l = hypot(dx, dy)
                if (l > 1e-3f) { lx = dx / l; ly = dy / l }
                hx[i] = lx; hy[i] = ly
            }
            // Before the first movement the heading is unset: back-fill from the first known one.
            val first = (0..n).firstOrNull { hx[it] != 0f || hy[it] != 0f }
            if (first != null) for (i in 0 until first) { hx[i] = hx[first]; hy[i] = hy[first] }
            val hsig = HEADING_SIGMA_SEC / dt
            val sx = gaussianSmooth(hx, hsig)
            val sy = gaussianSmooth(hy, hsig)
            for (i in 0..n) {
                val l = hypot(sx[i], sy[i])
                if (l > 1e-4f) { sx[i] /= l; sy[i] /= l }
            }
            val up = FloatArray(n + 1) { if (raw[it].penDown) 0f else 1f }
            val lifts = gaussianSmooth(up, LIFT_SIGMA_SEC / dt)
            return TipTrack(dur, source, cx, cy, sx, sy, lifts, speeds)
        }

        /**
         * Gaussian smoothing with std-dev [sigma] samples. Non-causal (looks ahead and behind); at the ends the
         * kernel is truncated and renormalised, so a constant signal stays constant. sigma <= 0 returns a copy.
         */
        fun gaussianSmooth(src: FloatArray, sigma: Float): FloatArray {
            if (sigma <= 1e-3f || src.size < 2) return src.copyOf()
            val r = max(1, (3f * sigma).toInt())
            val w = FloatArray(2 * r + 1) { exp(-0.5f * ((it - r) / sigma) * ((it - r) / sigma)) }
            val out = FloatArray(src.size)
            for (i in src.indices) {
                var acc = 0f; var wsum = 0f
                val lo = max(0, i - r); val hi = min(src.size - 1, i + r)
                for (j in lo..hi) { val k = w[j - i + r]; acc += src[j] * k; wsum += k }
                out[i] = acc / wsum
            }
            return out
        }

        // ---- cache shared by screen playback and every exporter (single entry, like LayerTimeline)

        private class Entry(val sig: LongArray, val track: TipTrack)

        private val lock = Any()
        private var cached: Entry? = null

        private fun signature(layers: List<Layer>, look: LookSettings, durationSec: Float): LongArray {
            val ls = layers.filter { it.visible && it.inAnimation }
            val out = LongArray(ls.size * 8 + 4)
            out[0] = ls.size.toLong(); out[1] = look.feel.ordinal.toLong()
            out[2] = durationSec.toBits().toLong(); out[3] = layers.size.toLong()
            var o = 4
            for (l in ls) {
                val st = l.strokes; val n = st.size
                out[o++] = l.id * 31 + layers.indexOf(l)
                out[o++] = l.animPriority.toLong() * 7 + l.kind.ordinal
                out[o++] = n.toLong()
                out[o++] = if (n > 0) System.identityHashCode(st[0]).toLong() else 0L
                out[o++] = if (n > 0) System.identityHashCode(st[n - 1]).toLong() else 0L
                out[o++] = if (n > 2) System.identityHashCode(st[n / 2]).toLong() else 0L
                out[o++] = l.inkRev.toLong() * 1_000_003L + (if (l.inkTexture != null) 1 else 0)
                out[o++] = l.revealWidthPx.toBits().toLong()
            }
            return out
        }

        /** Table for the current layers; rebuilt only when the layers, feel or duration change. */
        fun forLayers(layers: List<Layer>, look: LookSettings, durationSec: Float): TipTrack {
            val sig = signature(layers, look, durationSec)
            synchronized(lock) { cached?.let { if (it.sig.contentEquals(sig)) return it.track } }
            // Source of truth: anim/TimelineQuery via LayerTimeline.tipAt (layerIndex = index into [layers]).
            val track = build(durationSec, { p: Float ->
                val t = LayerTimeline.tipAt(layers, p, look, durationSec)
                Tip(t.x, t.y, t.layerIndex, t.strokeIndex, t.penDown, t.speed)
            })
            synchronized(lock) { cached = Entry(sig, track) }
            return track
        }

        /** Progress as drawn (onTwos-quantised) - what both screen and exporters feed the motion effects. */
        fun effective(progress: Float, durationSec: Float, look: LookSettings): Float =
            LookTiming.effectiveProgress(progress, durationSec, look)
    }
}
