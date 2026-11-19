package com.smitnk.sketcher.anim

import com.smitnk.sketcher.look.StrokeFeel
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max
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
    /** The next stroke's pen-up travel may begin this long before the previous stroke ends (human layers). */
    const val OVERLAP_SEC = 0.08

    /** One layer in play order. Arrays are per stroke; [speedMul] divides draw time and pause. */
    class LayerSpec(
        val id: Long,
        val lengths: FloatArray,
        val startX: FloatArray,
        val startY: FloatArray,
        val endX: FloatArray,
        val endY: FloatArray,
        val speedMul: FloatArray,
        /**
         * Pencil-reveal layers: pre-planned seconds per stroke (see [InkReveal.durations]). The reveal front moves at a
         * constant pace within each piece and there are no pen-lift pauses inside the layer, because a pause or a
         * slow-in/out shows nothing new on screen (the ink is only uncovered, never drawn).
         */
        val fixedSeconds: DoubleArray? = null,
        /**
         * Human-hand speed multiplier per stroke (see [HandSpeed]): curvature power law x seeded jitter. Non-null opts the
         * layer into the human model: draw time is divided by it and the pen-up travel overlaps the previous stroke's tail
         * by [OVERLAP_SEC]. Ignored for pencil-reveal (fixedSeconds) layers and for the plotter.
         */
        val handSpeed: FloatArray? = null,
        /** Optional polylines (x,y pairs) per stroke; only used by [TimelineQuery] to place the pen tip along a stroke. */
        val points: Array<FloatArray>? = null,
        /** Optional source stroke index per entry (pencil-reveal pieces map back to their stroke); default = entry index. */
        val strokeIndex: IntArray? = null,
    )

    /** Per-entry geometry for [Timeline.tipAt]; parallel to the timeline arrays. */
    internal class TipData(
        val pts: Array<FloatArray?>,
        val sx: FloatArray, val sy: FloatArray, val ex: FloatArray, val ey: FloatArray,
        val strokeIdx: IntArray,
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
        /** Per stroke: constant-speed reveal (pencil-reveal pieces); empty = none. */
        private val linear: BooleanArray = BooleanArray(0),
        internal val tip: TipData? = null,
    ) {
        val strokeCount get() = len.size

        /** Normalised (0..1) start/end time of stroke [i] in play order. */
        fun startOf(i: Int) = tStart[i]
        fun endOf(i: Int) = tEnd[i]

        val layerCount get() = layerIds.size
        fun layerIdAt(l: Int) = layerIds[l]

        /** Largest i with tStart[i] <= p (0 when p precedes the first stroke). */
        private fun locate(p: Double): Int {
            var lo = 0
            var hi = len.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (tStart[mid] <= p) lo = mid else hi = mid - 1
            }
            return lo
        }

        private fun shape(i: Int, u: Double): Double =
            if (linear.isNotEmpty() && linear[i]) u else easeFor(feel, u, if (ramp.isEmpty()) 0.0 else ramp[i].toDouble())

        /**
         * Allocation-free: writes the length allowance of every layer at [progress] (0..1) into [out] (ids, values and
         * totals, in play order). O(log n + layers). Only grows [out]'s arrays when the layer count increases.
         */
        fun fillAllowances(progress: Float, out: Allowances) {
            val p = progress.coerceIn(0f, 1f).toDouble()
            val nl = layerIds.size
            out.begin(nl)
            val ids = out.ids; val vals = out.values; val tot = out.totals
            for (l in 0 until nl) { ids[l] = layerIds[l]; tot[l] = layerTotal[l] }
            if (p >= 1.0 || len.isEmpty()) {
                for (l in 0 until nl) vals[l] = layerTotal[l]
                return
            }
            val cur = locate(p)
            val curLayer = strokeLayer[cur]
            val d = tEnd[cur] - tStart[cur]
            val u = if (d <= 0.0) 1.0 else ((p - tStart[cur]) / d).coerceIn(0.0, 1.0)
            val partial = (lenBefore[cur] + len[cur] * shape(cur, u).toFloat()).coerceIn(0f, layerTotal[curLayer])
            for (l in 0 until nl) {
                vals[l] = when {
                    l < curLayer -> layerTotal[l]
                    l == curLayer -> partial
                    else -> 0f
                }
            }
        }

        /** Length allowance per layer id at [progress] (0..1). Allocates a map: for tests / export; frames use [fillAllowances]. */
        fun allowances(progress: Float): Map<Long, Float> {
            val a = Allowances(layerIds.size)
            fillAllowances(progress, a)
            return a.toMap()
        }

        /**
         * Pen tip at [progress] (0..1), written into [out] (no allocation). In a stroke: on the path, penDown, speed in
         * image px per real second ([durationSec] = animation length). In the gap between strokes (pen-up travel, a
         * smoothstep from the last stroke's end to the next one's start): penDown = false and the indices are those of the
         * stroke about to be drawn. Before the first stroke / at the end the tip rests at the first start / last end.
         */
        fun tipAt(progress: Float, durationSec: Float, out: TipState) {
            val td = tip
            if (len.isEmpty() || td == null) { out.set(0f, 0f, -1, -1, false, 0f, 0L); return }
            val p = progress.coerceIn(0f, 1f).toDouble()
            val secs = durationSec.coerceAtLeast(0.5f).toDouble()
            if (p >= 1.0) {
                val l = len.size - 1
                out.set(td.ex[l], td.ey[l], strokeLayer[l], td.strokeIdx[l], false, 0f, layerIds[strokeLayer[l]])
                return
            }
            val i = locate(p)
            if (p < tStart[i]) { // before the very first stroke (i == 0)
                out.set(td.sx[0], td.sy[0], strokeLayer[0], td.strokeIdx[0], false, 0f, layerIds[strokeLayer[0]])
                return
            }
            if (p < tEnd[i]) {
                val d = tEnd[i] - tStart[i]
                val u = if (d <= 0.0) 1.0 else ((p - tStart[i]) / d).coerceIn(0.0, 1.0)
                val e = shape(i, u)
                val h = 1e-3
                val u0 = (u - h).coerceAtLeast(0.0); val u1 = (u + h).coerceAtMost(1.0)
                val slope = if (u1 > u0) (shape(i, u1) - shape(i, u0)) / (u1 - u0) else 0.0
                val sec = d * secs
                val speed = if (sec > 0.0) (len[i] * slope / sec).toFloat() else 0f
                pointAt(td, i, (e * len[i]).toFloat(), out)
                out.set(out.x, out.y, strokeLayer[i], td.strokeIdx[i], true, speed, layerIds[strokeLayer[i]])
                return
            }
            // gap between stroke i and i + 1 (i + 1 exists because tEnd[last] == 1 > p)
            val n = i + 1
            val gap = tStart[n] - tEnd[i]
            val w = if (gap <= 0.0) 1.0 else ((p - tEnd[i]) / gap).coerceIn(0.0, 1.0)
            val sm = w * w * (3 - 2 * w)
            val x0 = td.ex[i]; val y0 = td.ey[i]; val x1 = td.sx[n]; val y1 = td.sy[n]
            val dist = hypot((x1 - x0).toDouble(), (y1 - y0).toDouble())
            val sec = gap * secs
            val speed = if (sec > 0.0) (dist * 6 * w * (1 - w) / sec).toFloat() else 0f
            out.set((x0 + (x1 - x0) * sm).toFloat(), (y0 + (y1 - y0) * sm).toFloat(), strokeLayer[n], td.strokeIdx[n], false, speed, layerIds[strokeLayer[n]])
        }

        /** Writes the point at arc length [d] along entry [i] into out.x / out.y. */
        private fun pointAt(td: TipData, i: Int, d: Float, out: TipState) {
            val pts = td.pts[i]
            if (pts == null || pts.size < 4) {
                val f = if (len[i] > 0f) (d / len[i]).coerceIn(0f, 1f) else 0f
                out.x = td.sx[i] + (td.ex[i] - td.sx[i]) * f; out.y = td.sy[i] + (td.ey[i] - td.sy[i]) * f
                return
            }
            var rem = d
            var k = 2
            while (k < pts.size) {
                val dx = pts[k] - pts[k - 2]; val dy = pts[k + 1] - pts[k - 1]
                val seg = sqrt(dx * dx + dy * dy)
                if (seg >= rem && seg > 0f) {
                    val t = rem / seg
                    out.x = pts[k - 2] + dx * t; out.y = pts[k - 1] + dy * t
                    return
                }
                rem -= seg
                k += 2
            }
            out.x = pts[pts.size - 2]; out.y = pts[pts.size - 1]
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
        val lin = BooleanArray(n)
        val pause = DoubleArray(n) // pen-lift / layer pause BEFORE stroke k (0 for the first)
        val tip = TipData(
            arrayOfNulls(n), FloatArray(n), FloatArray(n), FloatArray(n), FloatArray(n), IntArray(n),
        )
        var drawTotal = 0.0
        var pauseTotal = 0.0
        var k = 0
        var havePrev = false
        for ((li, l) in layers.withIndex()) {
            var cum = 0f
            for (s in l.lengths.indices) {
                val mul = l.speedMul[s].toDouble().coerceAtLeast(0.01)
                val fixed = l.fixedSeconds
                if (fixed != null) lin[k] = true
                if (havePrev && (fixed == null || s == 0)) {
                    pause[k] = if (s == 0) LAYER_PAUSE_SEC else {
                        val dist = hypot(
                            (l.startX[s] - l.endX[s - 1]).toDouble(),
                            (l.startY[s] - l.endY[s - 1]).toDouble(),
                        )
                        if (plotter) {
                            // pen-up travel with its own trapezoid, plus the pen drop/lift cycle
                            (trapezoidTime(dist, SPEED_PX_PER_SEC * PLOT_TRAVEL_SPEED_MUL, PLOT_ACCEL * PLOT_TRAVEL_ACCEL_MUL) + PLOT_PEN_CYCLE_SEC) / mul
                        } else {
                            val lift = min(LIFT_MAX_SEC, LIFT_BASE_SEC + dist * LIFT_PER_PX_SEC) / mul
                            // human hand: the pen starts travelling during the last ~80 ms of the previous stroke
                            if (l.handSpeed != null && fixed == null) max(0.0, lift - OVERLAP_SEC) else lift
                        }
                    }
                    pauseTotal += pause[k]
                }
                // Tiny strokes (coverage hairs) must not cost a full stroke's setup time.
                val base = BASE_SEC * min(1.0, TINY_FLOOR + l.lengths[s] / TINY_LEN_PX)
                if (fixed != null) {
                    dur[k] = fixed[s]
                } else if (plotter) {
                    val L = l.lengths[s].toDouble()
                    dur[k] = maxOf(0.03, trapezoidTime(L, SPEED_PX_PER_SEC.toDouble(), PLOT_ACCEL)) / mul
                    ramp[k] = trapezoidRamp(L, SPEED_PX_PER_SEC.toDouble(), PLOT_ACCEL).toFloat()
                } else {
                    val hand = l.handSpeed?.get(s)?.toDouble()?.coerceAtLeast(0.05) ?: 1.0
                    dur[k] = (base + l.lengths[s] / (SPEED_PX_PER_SEC * hand)) / mul
                }
                tip.pts[k] = l.points?.get(s)
                tip.sx[k] = l.startX[s]; tip.sy[k] = l.startY[s]; tip.ex[k] = l.endX[s]; tip.ey[k] = l.endY[s]
                tip.strokeIdx[k] = l.strokeIndex?.get(s) ?: s
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
        return Timeline(ids, totals, sl, ts, te, ln, lb, t, feel, ramp, lin, tip)
    }
}
