package com.smitnk.sketcher.anim

import kotlin.math.hypot
import kotlin.math.min

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
            val partial = (lenBefore[cur] + len[cur] * ease(u).toFloat()).coerceIn(0f, layerTotal[curLayer])
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

    /** Smoothstep ease-in-out, 0..1 -> 0..1. */
    fun ease(u: Double): Double {
        val t = u.coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    fun build(layers: List<LayerSpec>): Timeline {
        var n = 0
        for (l in layers) n += l.lengths.size
        val ids = LongArray(layers.size) { layers[it].id }
        val totals = FloatArray(layers.size)
        val sl = IntArray(n)
        val ts = DoubleArray(n)
        val te = DoubleArray(n)
        val ln = FloatArray(n)
        val lb = FloatArray(n)
        var t = 0.0
        var k = 0
        var havePrev = false
        for ((li, l) in layers.withIndex()) {
            var cum = 0f
            for (s in l.lengths.indices) {
                val mul = l.speedMul[s].toDouble().coerceAtLeast(0.01)
                if (havePrev) {
                    t += if (s == 0) LAYER_PAUSE_SEC else {
                        val dist = hypot(
                            (l.startX[s] - l.endX[s - 1]).toDouble(),
                            (l.startY[s] - l.endY[s - 1]).toDouble(),
                        )
                        min(LIFT_MAX_SEC, LIFT_BASE_SEC + dist * LIFT_PER_PX_SEC) / mul
                    }
                }
                val dur = (BASE_SEC + l.lengths[s] / SPEED_PX_PER_SEC) / mul
                sl[k] = li; ts[k] = t; te[k] = t + dur; ln[k] = l.lengths[s]; lb[k] = cum
                t += dur
                cum += l.lengths[s]
                havePrev = true
                k++
            }
            totals[li] = cum
        }
        if (t > 0.0) for (i in 0 until n) { ts[i] /= t; te[i] /= t }
        return Timeline(ids, totals, sl, ts, te, ln, lb, t)
    }
}
