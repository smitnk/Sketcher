package com.smitnk.sketcher.anim

import com.smitnk.sketcher.image.BrushType
import com.smitnk.sketcher.look.LookSettings
import com.smitnk.sketcher.look.StrokeFeel
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

    private fun signature(ls: List<Layer>, feel: StrokeFeel): LongArray {
        val out = LongArray(ls.size * 9 + 2)
        out[0] = ls.size.toLong()
        out[1] = feel.ordinal.toLong()
        var o = 2
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
            val tex = l.inkTexture
            out[o++] = if (tex != null && !tex.isRecycled) System.identityHashCode(tex).toLong() * 1_000_003L + l.inkRev else 0L
            out[o++] = l.revealWidthPx.toBits().toLong()
        }
        return out
    }

    /**
     * Pencil-reveal layer: strokes are cut into pieces and every piece is timed by the ink it newly uncovers, so the
     * VISIBLE ink grows steadily instead of following path length. Null when the layer has no usable ink texture.
     */
    private fun inkSpec(l: Layer): DrawTimeline.LayerSpec? {
        val tex = l.inkTexture ?: return null
        if (tex.isRecycled || l.revealWidthPx <= 0f || l.strokes.isEmpty()) return null
        return try {
            val w = tex.width; val h = tex.height
            val px = IntArray(w * h)
            tex.getPixels(px, 0, w, 0, 0, w, h)
            val alpha = ByteArray(w * h) { (px[it] ushr 24).toByte() }
            val pts = ArrayList<FloatArray>(); val lens = ArrayList<Float>()
            for (s in l.strokes) {
                if (s.points.size < 2) { pts.add(floatArrayOf(0f, 0f)); lens.add(0f); continue }
                val c = InkReveal.chunk(s.points, s.length)
                pts.addAll(c.points); for (x in c.lengths) lens.add(x)
            }
            val n = pts.size
            val len = lens.toFloatArray()
            val ink = InkReveal.newInkPerPiece(alpha, w, h, pts, l.revealWidthPx / 2f)
            val naturalSec = len.sumOf { it.toDouble() } / DrawTimeline.SPEED_PX_PER_SEC
            val dur = InkReveal.durations(len, ink, naturalSec)
            val sx = FloatArray(n) { pts[it][0] }; val sy = FloatArray(n) { pts[it][1] }
            val ex = FloatArray(n) { pts[it][pts[it].size - 2] }; val ey = FloatArray(n) { pts[it][pts[it].size - 1] }
            DrawTimeline.LayerSpec(l.id, len, sx, sy, ex, ey, FloatArray(n) { 1f }, dur)
        } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }
    }

    private fun build(ls: List<Layer>, feel: StrokeFeel): DrawTimeline.Timeline = DrawTimeline.build(ls.map { l ->
        inkSpec(l)?.let { return@map it }
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
    }, feel)

    fun timelineFor(layers: List<Layer>, look: LookSettings = LookSettings()): DrawTimeline.Timeline {
        val ls = playing(layers)
        val sig = signature(ls, look.feel)
        synchronized(lock) { cached?.let { if (it.sig.contentEquals(sig)) return it.timeline } }
        val tl = build(ls, look.feel)
        synchronized(lock) { cached = Entry(sig, tl) }
        return tl
    }

    /** Per layer id length allowance at [progress]; hidden / excluded layers have no entry. */
    fun allowances(
        layers: List<Layer>,
        progress: Float,
        look: LookSettings = LookSettings(),
        durationSec: Float = AnimationController.DEFAULT_DURATION_SEC,
    ): Map<Long, Float> =
        timelineFor(layers, look).allowances(LookTiming.effectiveProgress(progress, durationSec, look))
}
