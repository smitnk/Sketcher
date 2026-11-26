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

    /** Scratch reused by every [timelineFor] call (guarded by [lock]) so a cache hit allocates nothing. */
    private var order = IntArray(16)
    private var scratch = LongArray(16 * SIG_REC + 2)

    /**
     * Fills [order] with the indices of the visible, in-animation layers sorted by animPriority (stable: ties keep list
     * order; insertion sort, no allocation) and returns their count. Caller holds [lock].
     */
    private fun fillOrder(layers: List<Layer>): Int {
        val n = layers.size
        if (order.size < n) order = IntArray(n + 16)
        val o = order
        var c = 0
        for (i in 0 until n) {
            val l = layers[i]
            if (!l.visible || !l.inAnimation || l.animPriority == CONCURRENT_PRIORITY) continue
            var j = c
            while (j > 0 && layers[o[j - 1]].animPriority > l.animPriority) { o[j] = o[j - 1]; j-- }
            o[j] = i
            c++
        }
        return c
    }

    /** Writes the signature of the first [cnt] layers of [order] into [scratch] and returns its length. Caller holds [lock]. */
    private fun fillSignature(layers: List<Layer>, cnt: Int, feel: StrokeFeel): Int {
        val need = cnt * SIG_REC + 2
        if (scratch.size < need) scratch = LongArray(need + 16 * SIG_REC)
        val out = scratch
        out[0] = cnt.toLong()
        out[1] = feel.ordinal.toLong()
        var o = 2
        for (k in 0 until cnt) {
            val l = layers[order[k]]
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
        return need
    }

    private fun sameSig(a: LongArray, b: LongArray, len: Int): Boolean {
        if (a.size != len) return false
        for (i in 0 until len) if (a[i] != b[i]) return false
        return true
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
            val pts = ArrayList<FloatArray>(); val lens = ArrayList<Float>(); val sIdx = ArrayList<Int>()
            for ((si, s) in l.strokes.withIndex()) {
                if (s.points.size < 2) { pts.add(floatArrayOf(0f, 0f)); lens.add(0f); sIdx.add(si); continue }
                val c = InkReveal.chunk(s.points, s.length)
                pts.addAll(c.points); for (x in c.lengths) lens.add(x)
                for (x in c.lengths) sIdx.add(si)
            }
            val n = pts.size
            val len = lens.toFloatArray()
            val ink = InkReveal.newInkPerPiece(alpha, w, h, pts, l.revealWidthPx / 2f)
            val naturalSec = len.sumOf { it.toDouble() } / DrawTimeline.SPEED_PX_PER_SEC
            val dur = InkReveal.durations(len, ink, naturalSec)
            val sx = FloatArray(n) { pts[it][0] }; val sy = FloatArray(n) { pts[it][1] }
            val ex = FloatArray(n) { pts[it][pts[it].size - 2] }; val ey = FloatArray(n) { pts[it][pts[it].size - 1] }
            DrawTimeline.LayerSpec(l.id, len, sx, sy, ex, ey, FloatArray(n) { 1f }, dur, points = pts.toTypedArray(), strokeIndex = sIdx.toIntArray())
        } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }
    }

    private fun build(ls: List<Layer>, feel: StrokeFeel): DrawTimeline.Timeline = DrawTimeline.build(ls.map { l ->
        inkSpec(l)?.let { return@map it }
        val n = l.strokes.size
        val sx = FloatArray(n); val sy = FloatArray(n); val ex = FloatArray(n); val ey = FloatArray(n)
        val len = FloatArray(n)
        val mul = FloatArray(n)
        val human = feel == StrokeFeel.CLEAN || feel == StrokeFeel.WHITEBOARD_MARKER
        val hand = if (human) FloatArray(n) else null
        for (i in 0 until n) {
            val s = l.strokes[i]
            val p = s.points
            if (p.size >= 2) { sx[i] = p[0]; sy[i] = p[1]; ex[i] = p[p.size - 2]; ey[i] = p[p.size - 1] }
            len[i] = s.length
            if (hand != null) hand[i] = HandSpeed.strokeMul(l.id, i, p, s.length)
            mul[i] = when {
                // background draws quicker than the character (shorter share of the animation)
                l.kind == LayerKind.AUTO_BG_OUTLINE -> BG_MUL
                l.kind == LayerKind.AUTO_BG_FILL -> 2f * BG_MUL
                l.kind == LayerKind.AUTO_FILL -> DrawTimeline.FILL_MUL.toFloat()
                l.kind == LayerKind.AUTO_SHADING || s.brush == BrushType.HATCH -> DrawTimeline.SHADING_MUL.toFloat()
                else -> 1f
            }
        }
        DrawTimeline.LayerSpec(l.id, len, sx, sy, ex, ey, mul, handSpeed = hand, points = Array(n) { l.strokes[it].points })
    }, feel)

    /** Cached timeline for [layers] / [look]. A cache hit allocates nothing (scratch signature compared in place). */
    fun timelineFor(layers: List<Layer>, look: LookSettings = LookSettings()): DrawTimeline.Timeline {
        var ls: List<Layer> = emptyList()
        var sig = EMPTY_SIG
        synchronized(lock) {
            val cnt = fillOrder(layers)
            val len = fillSignature(layers, cnt, look.feel)
            val c = cached
            if (c != null && sameSig(c.sig, scratch, len)) return c.timeline
            sig = scratch.copyOf(len)
            ls = ArrayList<Layer>(cnt).also { for (k in 0 until cnt) it.add(layers[order[k]]) }
        }
        val tl = build(ls, look.feel)
        synchronized(lock) { cached = Entry(sig, tl) }
        return tl
    }

    /**
     * Fills [out] with the per-layer length allowance at [progress]; hidden / excluded layers have no entry. Pass a reused
     * [Allowances] for a zero-allocation frame (cache hit); the default allocates a fresh one (safe across threads).
     */
    fun allowances(
        layers: List<Layer>,
        progress: Float,
        look: LookSettings = LookSettings(),
        durationSec: Float = AnimationController.DEFAULT_DURATION_SEC,
        out: Allowances = Allowances(),
    ): Allowances {
        val p = LookTiming.effectiveProgress(progress, durationSec, look)
        timelineFor(layers, look).fillAllowances(p, out)
        // CONCURRENT layers (background "same time") reveal evenly over the whole animation, beside the timeline.
        for (i in 0 until layers.size) {
            val l = layers[i]
            if (l.animPriority != CONCURRENT_PRIORITY || !l.visible || !l.inAnimation) continue
            var total = 0f
            for (k in 0 until l.strokes.size) total += l.strokes[k].length
            out.add(l.id, total * p.coerceIn(0f, 1f), total)
        }
        return out
    }

    /**
     * Pen tip for a project's [layers] at [progress] (same cached timeline as [allowances]); [TipState.layerIndex] is the
     * index into [layers], [TipState.strokeIndex] the stroke inside it. See [TimelineQuery] and docs/wiring/tip.md.
     */
    fun tipAt(
        layers: List<Layer>,
        progress: Float,
        look: LookSettings = LookSettings(),
        durationSec: Float = AnimationController.DEFAULT_DURATION_SEC,
        out: TipState = TipState(),
    ): TipState {
        TimelineQuery.tipAt(timelineFor(layers, look), LookTiming.effectiveProgress(progress, durationSec, look), durationSec, out)
        if (out.layerId >= 0L) {
            var idx = -1
            for (i in 0 until layers.size) if (layers[i].id == out.layerId) { idx = i; break }
            out.layerIndex = idx
        }
        return out
    }

    /** animPriority of a layer drawn at the same time as the rest instead of in sequence (see [allowances]). */
    const val CONCURRENT_PRIORITY = Int.MIN_VALUE

    private const val BG_MUL = 3f
    private const val SIG_REC = 9
    private val EMPTY_SIG = LongArray(0)
}
