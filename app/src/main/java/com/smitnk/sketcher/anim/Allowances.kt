package com.smitnk.sketcher.anim

/**
 * Reusable, allocation-free per-layer reveal budget for one frame: parallel arrays (layer id, revealed length, layer total)
 * in play order. [AnimationController] owns one instance and refills it each frame ([DrawTimeline.Timeline.fillAllowances]);
 * the renderer reads it with [budget] / [isPartial] / [isFull] (primitive returns, no boxing). Layers that are hidden or
 * excluded from the animation have no entry (= drawn fully). Not thread-safe: one writer per instance.
 */
class Allowances(capacity: Int = 8) {
    var size = 0
        private set
    internal var ids = LongArray(capacity.coerceAtLeast(1))
        private set
    internal var values = FloatArray(capacity.coerceAtLeast(1))
        private set
    internal var totals = FloatArray(capacity.coerceAtLeast(1))
        private set

    /** Prepares for [n] entries; allocates only when the capacity grows. */
    internal fun begin(n: Int) {
        if (ids.size < n) {
            val c = maxOf(n, ids.size * 2)
            ids = LongArray(c); values = FloatArray(c); totals = FloatArray(c)
        }
        size = n
    }

    private fun indexOf(id: Long): Int {
        val a = ids
        for (i in 0 until size) if (a[i] == id) return i
        return -1
    }

    fun isEmpty() = size == 0

    /** Revealed length of layer [id]; [Float.MAX_VALUE] when it has no entry (draw everything). */
    fun budget(id: Long): Float {
        val i = indexOf(id)
        return if (i < 0) Float.MAX_VALUE else values[i]
    }

    /** True when layer [id] is animated and not yet completely revealed. */
    fun isPartial(id: Long): Boolean {
        val i = indexOf(id)
        return i >= 0 && values[i] < totals[i] - 0.01f
    }

    /** True when layer [id] has no entry or is completely revealed. */
    fun isFull(id: Long): Boolean = !isPartial(id)

    /** Boxing lookup for cold paths (debug, tests): null when the layer has no entry. */
    operator fun get(id: Long): Float? {
        val i = indexOf(id)
        return if (i < 0) null else values[i]
    }

    fun toMap(): Map<Long, Float> {
        val m = HashMap<Long, Float>(size * 2 + 1)
        for (i in 0 until size) m[ids[i]] = values[i]
        return m
    }
}
