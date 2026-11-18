package com.smitnk.sketcher.anim

import com.smitnk.sketcher.look.StrokeFeel
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure-Kotlin "human artist" time model. Each stroke takes base + length/speed to draw, eased in/out
 * (pen accelerates, decelerates); a pen-lift pause proportional to the jump distance sits between
 * strokes and a longer one between layers. The raw timeline is normalised so progress 0..1 spans it.
 */
object DrawTimeline {
    const val BASE_SEC = 0.15
    const val SPEED_PX_PER_SEC = 500.0
    const val LIFT_BASE_SEC = 0.04
    const val LIFT_PER_PX_SEC = 1.0 / 1500.0
    const val LIFT_MAX_SEC = 0.45
    const val LAYER_PAUSE_SEC = 0.9
    const val SHADING_MUL = 2.5
    const val FILL_MUL = 3.0
    /** Pen-lift + layer pauses together never exceed this share of the timeline. */
    const val MAX_PAUSE_FRACTION = 0.25
    const val EASE_MIX = 0.6
    /** Pen-plotter kinematics in px: cruise speed = [SPEED_PX_PER_SEC], acceleration chosen for a 0.15 s ramp. */
    const val PLOT_ACCEL = SPEED_PX_PER_SEC / 0.15
    const val PLOT_TRAVEL_SPEED_MUL = 2.9
    const val PLOT_TRAVEL_ACCEL_MUL = 2.0
    /** Pen drop + lift per stroke (seconds). */
    const val PLOT_PEN_CYCLE_SEC = 0.155
    const val PLOT_MAX_PAUSE_FRACTION = 0.4
    private const val TINY_FLOOR = 0.2
    private const val TINY_LEN_PX = 60.0

    /** One layer in play order. Arrays are per stroke; [speedMul] divides draw time and pause. */
    class LayerSpec(
        val id: Long,
        val lengths: FloatArray,
        val startX: FloatArray,
        val startY: FloatArray,
        val endX: FloatArray,
        val endY: FloatArray,
        val speedMul: FloatArray,
    )

    class Timeline internal constructor(
        private val layerIds: LongArray,
        private val layerTotal: FloatArray,
        private val strokeLayer: IntArray,
        private val tStart: DoubleArray,
        private val tEnd: DoubleArray,
        private val len: FloatArray,
        private val lenBefore: FloatArray,
        /** Unscaled length of the whole timeline in seconds. */
        val rawSeconds: Double,
        private val feel: StrokeFeel = StrokeFeel.CLEAN,
        /** Plotter only: acceleration-ramp share (0..0.5) of each stroke's time; empty otherwise. */
        private val ramp: FloatArray = FloatArray(0),
    ) {
        val strokeCount get() = len.size

        /** Normalised (0..1) start/end time of stroke [i] in play order. */
        fun startOf(i: Int) = tStart[i]
        fun endOf(i: Int) = tEnd[i]

        /** Length allowance per layer id at [progress] (0..1). O(log n + layers). */
        fun allowances(progress: Float): Map<Long, Float> {
            val p = progress.coerceIn(0f, 1f).toDouble()
            val map = HashMap<Long, Float>(layerIds.size * 2)
            if (p >= 1.0 || len.isEmpty()) {
                for (l in layerIds.indices) map[layerIds[l]] = layerTotal[l]
                return map
            }
            // largest i with tStart[i] <= p
            var lo = 0
            var hi = len.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (tStart[mid] <= p) lo = mid else hi = mid - 1
            }
            val cur = lo
            val curLayer = strokeLayer[cur]
            val d = tEnd[cur] - tStart[cur]
            val u = if (d <= 0.0) 1.0 else ((p - tStart[cur]) / d).coerceIn(0.0, 1.0)
            val partial = (lenBefore[cur] + len[cur] * easeFor(feel, u, if (ramp.isEmpty()) 0.0 else ramp[cur].toDouble()).toFloat()).coerceIn(0f, layerTotal[curLayer])
            for (l in layerIds.indices) {
                map[layerIds[l]] = when {
                    l < curLayer -> layerTotal[l]
                    l == curLayer -> partial
                    else -> 0f
                }
            }
            return map
        }
    }

    /** Mild ease-in-out (60% smoothstep, 40% linear), 0..1 -> 0..1: the pen slows at stroke ends without stalling. */
    fun ease(u: Double): Double {
        val t = u.coerceIn(0.0, 1.0)
        return t + EASE_MIX * (t * t * (3 - 2 * t) - t)
    }

    /** Whiteboard hand speed: slow in, fast middle, slow out (never stalls: derivative >= 0.2). 0..1 -> 0..1, monotonic. */
    fun easeWhiteboard(u: Double): Double {
        val t = u.coerceIn(0.0, 1.0)
        return t - sin(2 * PI * t) / (2 * PI) * 0.8
    }

    /** Trapezoidal velocity profile: distance fraction at time fraction [u] with accel/decel ramps of [r] (0..0.5) each. */
    fun easeTrapezoid(u: Double, r: Double): Double {
        val t = u.coerceIn(0.0, 1.0)
        val rr = r.coerceIn(1e-6, 0.5)
        val k = 2 * rr * (1 - rr)
        return when {
            t < rr -> t * t / k
            t > 1 - rr -> 1 - (1 - t) * (1 - t) / k
            else -> (t - rr / 2) / (1 - rr)
        }
    }

    fun easeFor(feel: StrokeFeel, u: Double, ramp: Double): Double = when (feel) {
        StrokeFeel.WHITEBOARD_MARKER -> easeWhiteboard(u)
        StrokeFeel.PEN_PLOTTER -> easeTrapezoid(u, ramp)
        else -> ease(u)
    }

    /** Time for a trapezoidal move of [dist] at cruise [v] and acceleration [a]. */
    fun trapezoidTime(dist: Double, v: Double, a: Double): Double =
        if (dist <= 0.0) 0.0 else if (dist >= v * v / a) dist / v + v / a else 2 * sqrt(dist / a)

    /** Share of a trapezoid move's time spent accelerating (and, equally, decelerating). */
    fun trapezoidRamp(dist: Double, v: Double, a: Double): Double {
        val t = trapezoidTime(dist, v, a)
        if (t <= 0.0) return 0.5
        val ta = if (dist >= v * v / a) v / a else sqrt(dist / a)
        return (ta / t).coerceIn(1e-6, 0.5)
    }

    fun build(layers: List<LayerSpec>, feel: StrokeFeel = StrokeFeel.CLEAN): Timeline {
        val plotter = feel == StrokeFeel.PEN_PLOTTER
        val ramp = if (plotter) FloatArray(layers.sumOf { it.lengths.size }) else FloatArray(0)
        var n = 0
        for (l in layers) n += l.lengths.size
        val ids = LongArray(layers.size) { layers[it].id }
        val totals = FloatArray(layers.size)
        val sl = IntArray(n)
        val ts = DoubleArray(n)
        val te = DoubleArray(n)
        val ln = FloatArray(n)
        val lb = FloatArray(n)
        val dur = DoubleArray(n)
        val pause = DoubleArray(n) // pen-lift / layer pause BEFORE stroke k (0 for the first)
        var drawTotal = 0.0
        var pauseTotal = 0.0
        var k = 0
        var havePrev = false
        for ((li, l) in layers.withIndex()) {
            var cum = 0f
            for (s in l.lengths.indices) {
                val mul = l.speedMul[s].toDouble().coerceAtLeast(0.01)
                if (havePrev) {
                    pause[k] = if (s == 0) LAYER_PAUSE_SEC else {
                        val dist = hypot(
                            (l.startX[s] - l.endX[s - 1]).toDouble(),
                            (l.startY[s] - l.endY[s - 1]).toDouble(),
                        )
                        if (plotter) {
                            // pen-up travel with its own trapezoid, plus the pen drop/lift cycle
                            (trapezoidTime(dist, SPEED_PX_PER_SEC * PLOT_TRAVEL_SPEED_MUL, PLOT_ACCEL * PLOT_TRAVEL_ACCEL_MUL) + PLOT_PEN_CYCLE_SEC) / mul
                        } else min(LIFT_MAX_SEC, LIFT_BASE_SEC + dist * LIFT_PER_PX_SEC) / mul
                    }
                    pauseTotal += pause[k]
                }
                // Tiny strokes (coverage hairs) must not cost a full stroke's setup time.
                val base = BASE_SEC * min(1.0, TINY_FLOOR + l.lengths[s] / TINY_LEN_PX)
                if (plotter) {
                    val L = l.lengths[s].toDouble()
                    dur[k] = maxOf(0.03, trapezoidTime(L, SPEED_PX_PER_SEC.toDouble(), PLOT_ACCEL)) / mul
                    ramp[k] = trapezoidRamp(L, SPEED_PX_PER_SEC.toDouble(), PLOT_ACCEL).toFloat()
                } else dur[k] = (base + l.lengths[s] / SPEED_PX_PER_SEC) / mul
                drawTotal += dur[k]
                sl[k] = li; ln[k] = l.lengths[s]; lb[k] = cum
                cum += l.lengths[s]
                havePrev = true
                k++
            }
            totals[li] = cum
        }
        // Pauses may take at most MAX_PAUSE_FRACTION of the whole timeline, so they never eat the tail.
        val pf = if (plotter) PLOT_MAX_PAUSE_FRACTION else MAX_PAUSE_FRACTION
        val maxPause = drawTotal * pf / (1.0 - pf)
        val f = if (pauseTotal > maxPause && pauseTotal > 0.0) maxPause / pauseTotal else 1.0
        var t = 0.0
        for (i in 0 until n) {
            t += pause[i] * f
            ts[i] = t; t += dur[i]; te[i] = t
        }
        if (t > 0.0) for (i in 0 until n) { ts[i] /= t; te[i] /= t }
        if (n > 0) te[n - 1] = 1.0 // exact: progress 1 == the last stroke completes
        return Timeline(ids, totals, sl, ts, te, ln, lb, t, feel, ramp)
    }
}
