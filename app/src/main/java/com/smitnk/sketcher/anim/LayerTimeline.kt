package com.smitnk.sketcher.anim

import com.smitnk.sketcher.image.BrushType
import com.smitnk.sketcher.model.Layer
import com.smitnk.sketcher.model.LayerKind

/** Layer -> [DrawTimeline] adapter with a single-entry cache shared by screen playback and export. */
object LayerTimeline {
    private class Entry(val sig: LongArray, val timeline: DrawTimeline.Timeline)

    private val lock = Any()
    private var cached: Entry? = null

    /** Visible, in-animation layers sorted by animPriority (stable: ties keep list order). */
    private fun playing(layers: List<Layer>): List<Layer> =
        layers.filter { it.visible && it.inAnimation }.sortedBy { it.animPriority }

    private fun signature(ls: List<Layer>): LongArray {
        val out = LongArray(ls.size * 7 + 1)
        out[0] = ls.size.toLong()
        var o = 1
        for (l in ls) {
            val st = l.strokes
            val n = st.size
            out[o++] = l.id
            out[o++] = l.kind.ordinal.toLong() * 1_000_003L + l.animPriority
            out[o++] = n.toLong()
            out[o++] = if (n > 0) System.identityHashCode(st[0]).toLong() else 0L
            out[o++] = if (n > 0) System.identityHashCode(st[n - 1]).toLong() else 0L
            out[o++] = if (n > 2) System.identityHashCode(st[n / 2]).toLong() else 0L
            out[o++] = if (n > 0) st[0].length.toBits().toLong() else 0L
        }
        return out
    }

    private fun build(ls: List<Layer>): DrawTimeline.Timeline = DrawTimeline.build(ls.map { l ->
        val n = l.strokes.size
        val sx = FloatArray(n); val sy = FloatArray(n); val ex = FloatArray(n); val ey = FloatArray(n)
        val len = FloatArray(n)
        val mul = FloatArray(n)
        for (i in 0 until n) {
            val s = l.strokes[i]
            val p = s.points
            if (p.size >= 2) { sx[i] = p[0]; sy[i] = p[1]; ex[i] = p[p.size - 2]; ey[i] = p[p.size - 1] }
            len[i] = s.length
            mul[i] = when {
                l.kind == LayerKind.AUTO_FILL -> DrawTimeline.FILL_MUL.toFloat()
                l.kind == LayerKind.AUTO_SHADING || s.brush == BrushType.HATCH -> DrawTimeline.SHADING_MUL.toFloat()
                else -> 1f
            }
        }
        DrawTimeline.LayerSpec(l.id, len, sx, sy, ex, ey, mul)
    })

    fun timelineFor(layers: List<Layer>): DrawTimeline.Timeline {
        val ls = playing(layers)
        val sig = signature(ls)
        synchronized(lock) { cached?.let { if (it.sig.contentEquals(sig)) return it.timeline } }
        val tl = build(ls)
        synchronized(lock) { cached = Entry(sig, tl) }
        return tl
    }

    /** Per layer id length allowance at [progress]; hidden / excluded layers have no entry. */
    fun allowances(layers: List<Layer>, progress: Float): Map<Long, Float> =
        timelineFor(layers).allowances(progress)
}
