package com.smitnk.sketcher.look

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Pure-Kotlin "hand feel" geometry for strokes (no android.* imports: unit-testable on the JVM).
 * Ported to Kotlin from the MIT-licensed JS engines of Lemo-Opuscar (whiteboard wb.js, pen-plotter plot.js,
 * scratch-film base.js), https://github.com/lemomo-ai/lemo-opuscar, MIT License, modified.
 *
 * A [Shape] is derived from a stroke's own points and is fully deterministic in (points, width, feel, seed, drawing).
 * Reveal (animation) is expressed as a fraction 0..1 of the ORIGINAL stroke length so the shape's own length never
 * leaks into the timeline: fraction 1 always shows the complete shape.
 */
object StrokeFeelGeometry {

    /** One polyline of a [Shape]. It is revealed over the stroke fraction [from]..[to]. */
    class Line(
        val pts: FloatArray,
        val from: Float,
        val to: Float,
        val widthMul: Float,
        val alphaMul: Float,
    ) {
        val length: Float by lazy(LazyThreadSafetyMode.PUBLICATION) { polyLength(pts) }

        /** Length of this line to draw when the stroke is revealed up to fraction [f] (0..1+). */
        fun limitFor(f: Float): Float {
            if (f >= to) return length
            if (f <= from) return 0f
            return length * ((f - from) / (to - from))
        }
    }

    /**
     * [lines] are stroked polylines; when [half] is not null it holds per-point half widths of lines[0],
     * which is then drawn as a filled ribbon (see [ribbonOutline]). [dotR] > 0 adds a filled dot at [dotX],[dotY]
     * (pooled ink where the pen lands) as soon as any of the stroke is revealed.
     */
    class Shape(
        val lines: Array<Line>,
        val half: FloatArray?,
        val dotR: Float,
        val dotX: Float,
        val dotY: Float,
    )

    // ------------------------------------------------------------------ deterministic noise

    internal fun hash(n: Int): Double {
        var x = n * -0x61c88647
        x = (x xor (x ushr 16)) * 0x45d9f3b
        x = (x xor (x ushr 16)) * 0x45d9f3b
        x = x xor (x ushr 16)
        return (x ushr 8) / 16777216.0
    }

    /** Smooth 1-D value noise in 0..1. */
    internal fun vnoise(x: Double, seed: Int): Double {
        val i = floor(x)
        val f = x - i
        val u = f * f * (3 - 2 * f)
        val ii = i.toInt()
        val a = hash(ii * 31 + seed)
        val b = hash((ii + 1) * 31 + seed)
        return a + (b - a) * u
    }

    /** Seeded generator (mulberry32), 0..1. */
    class Rng(seed: Int) {
        private var s = seed
        fun next(): Double {
            s += 0x6D2B79F5
            var t = (s xor (s ushr 15)) * (1 or s)
            t = (t + (t xor (t ushr 7)) * (61 or t)) xor t
            return ((t xor (t ushr 14)).toLong() and 0xFFFFFFFFL) / 4294967296.0
        }
        fun range(a: Double, b: Double) = a + (b - a) * next()
    }

    fun mix(a: Int, b: Int): Int {
        var h = a * -0x61c88647 + b
        h = (h xor (h ushr 15)) * -0x7a143595
        h = (h xor (h ushr 13)) * -0x3d4d51cb
        return h xor (h ushr 16)
    }

    // ------------------------------------------------------------------ geometry primitives

    fun polyLength(p: FloatArray): Float {
        var t = 0.0
        var i = 2
        while (i < p.size) { t += hypot((p[i] - p[i - 2]).toDouble(), (p[i + 1] - p[i - 1]).toDouble()); i += 2 }
        return t.toFloat()
    }

    /** Evenly spaced points ([step] apart along the path); first and last point are kept. */
    fun resample(p: FloatArray, step: Float): FloatArray {
        if (p.size < 4 || step <= 0f) return p.copyOf()
        val o = ArrayList<Float>(p.size)
        o.add(p[0]); o.add(p[1])
        var carry = 0.0
        var i = 2
        while (i < p.size) {
            val ax = p[i - 2].toDouble(); val ay = p[i - 1].toDouble()
            val bx = p[i].toDouble(); val by = p[i + 1].toDouble()
            val d = hypot(bx - ax, by - ay)
            var s = step - carry
            while (s <= d) {
                o.add((ax + (bx - ax) * s / d).toFloat()); o.add((ay + (by - ay) * s / d).toFloat())
                s += step
            }
            carry = d - (s - step)
            i += 2
        }
        val n = p.size
        val lx = o[o.size - 2]; val ly = o[o.size - 1]
        if (hypot((lx - p[n - 2]).toDouble(), (ly - p[n - 1]).toDouble()) > step * 0.25) { o.add(p[n - 2]); o.add(p[n - 1]) }
        else { o[o.size - 2] = p[n - 2]; o[o.size - 1] = p[n - 1] }
        return o.toFloatArray()
    }

    /** Low-frequency hand wobble: offset along the normal, [amp] units, wavelength [wav] units of arc length. */
    fun wobble(p: FloatArray, amp: Float, wav: Float, seed: Int): FloatArray {
        if (p.size < 6 || amp <= 0f) return p.copyOf()
        val q = p.copyOf()
        var s = 0.0
        var i = 0
        while (i < p.size) {
            val j = min(i, p.size - 4)
            val tx = (p[j + 2] - p[j]).toDouble(); val ty = (p[j + 3] - p[j + 1]).toDouble()
            val tl = hypot(tx, ty).let { if (it == 0.0) 1.0 else it }
            if (i > 0) s += hypot((p[i] - p[i - 2]).toDouble(), (p[i + 1] - p[i - 1]).toDouble())
            val off = amp * ((vnoise(s / wav, seed) - 0.5) * 2 + 0.45 * (vnoise(s / (wav * 0.23), seed + 77) - 0.5))
            q[i] = (p[i] - ty / tl * off).toFloat()
            q[i + 1] = (p[i + 1] + tx / tl * off).toFloat()
            i += 2
        }
        // The ends stay anchored so closed shapes still meet and strokes start where the user put the pen.
        q[0] = p[0]; q[1] = p[1]; q[q.size - 2] = p[p.size - 2]; q[q.size - 1] = p[p.size - 1]
        return q
    }

    /** True when the end of the polyline lands near its start (a hand-drawn loop). */
    fun isClosed(p: FloatArray, width: Float): Boolean {
        if (p.size < 8) return false
        val len = polyLength(p)
        val d = hypot((p[p.size - 2] - p[0]).toDouble(), (p[p.size - 1] - p[1]).toDouble())
        return len > width * 6f && d < max(width * 2.5, len * 0.12)
    }

    /** Continues the path past its end by [over] units with a tiny hook (a loop never closes exactly). */
    fun closureOvershoot(p: FloatArray, over: Float, seed: Int): FloatArray {
        if (p.size < 6 || over <= 0f) return p.copyOf()
        val n = p.size
        val tx = (p[n - 2] - p[n - 4]).toDouble(); val ty = (p[n - 1] - p[n - 3]).toDouble()
        val tl = hypot(tx, ty).let { if (it == 0.0) 1.0 else it }
        val hk = (hash(seed) - 0.5) * 0.5
        val out = p.copyOf(n + 8)
        for (k in 1..4) {
            val f = over * k / 4.0
            val a = hk * k / 4.0
            out[n - 2 + 2 * k] = (p[n - 2] + (tx / tl * cos(a) - ty / tl * sin(a)) * f).toFloat()
            out[n - 1 + 2 * k] = (p[n - 1] + (ty / tl * cos(a) + tx / tl * sin(a)) * f).toFloat()
        }
        return out
    }

    /** Per-point half widths of a chisel marker: pressure ramps in at the start, tapers at the end. */
    fun pressureHalfWidths(p: FloatArray, width: Float): FloatArray {
        val m = p.size / 2
        val h = FloatArray(m)
        if (m == 0) return h
        val cum = DoubleArray(m)
        for (i in 1 until m) cum[i] = cum[i - 1] + hypot((p[2 * i] - p[2 * i - 2]).toDouble(), (p[2 * i + 1] - p[2 * i - 1]).toDouble())
        val len = cum[m - 1]
        for (i in 0 until m) {
            val a = max(0, i - 1); val b = min(m - 1, i + 1)
            val th = atan2((p[2 * b + 1] - p[2 * a + 1]).toDouble(), (p[2 * b] - p[2 * a]).toDouble())
            val d = cum[i]
            val tail = ((d - (len - width * 2.0)) / (width * 2.0)).coerceIn(0.0, 1.0)
            val pr = min(1.0, 0.72 + d / (width * 2.2)) * (1 - 0.12 * tail)
            h[i] = (width * 0.5 * (0.8 + 0.2 * abs(sin(th - 2.35))) * pr).toFloat()
        }
        return h
    }

    /**
     * Closed polygon (flat x,y) of the ribbon around [p] with per-point half widths [half], revealed up to [limit]
     * units of arc length (limit >= length shows everything). Round end caps are approximated by 6 points each.
     */
    fun ribbonOutline(p: FloatArray, half: FloatArray, limit: Float): FloatArray {
        val n = p.size / 2
        if (n == 0 || limit <= 0f) return FloatArray(0)
        val xs = ArrayList<Float>(n + 1); val ys = ArrayList<Float>(n + 1); val hs = ArrayList<Float>(n + 1)
        xs.add(p[0]); ys.add(p[1]); hs.add(half[0])
        var rem = limit
        for (i in 1 until n) {
            val dx = p[2 * i] - p[2 * i - 2]; val dy = p[2 * i + 1] - p[2 * i - 1]
            val d = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            if (d <= rem) { xs.add(p[2 * i]); ys.add(p[2 * i + 1]); hs.add(half[i]); rem -= d }
            else {
                val t = if (d > 0f) rem / d else 0f
                xs.add(p[2 * i - 2] + dx * t); ys.add(p[2 * i - 1] + dy * t); hs.add(half[i - 1] + (half[i] - half[i - 1]) * t)
                break
            }
        }
        val m = xs.size
        val nx = FloatArray(m); val ny = FloatArray(m)
        for (i in 0 until m) {
            val a = max(0, i - 1); val b = min(m - 1, i + 1)
            val tx = (xs[b] - xs[a]).toDouble(); val ty = (ys[b] - ys[a]).toDouble()
            val tl = hypot(tx, ty).let { if (it == 0.0) 1.0 else it }
            nx[i] = (-ty / tl).toFloat(); ny[i] = (tx / tl).toFloat()
        }
        val out = ArrayList<Float>((m * 2 + 14) * 2)
        for (i in 0 until m) { out.add(xs[i] + nx[i] * hs[i]); out.add(ys[i] + ny[i] * hs[i]) }
        val capSteps = 6
        // end cap: from +normal sweeping through the tangent direction to -normal
        val ae = atan2(ny[m - 1].toDouble(), nx[m - 1].toDouble())
        for (k in 1 until capSteps) {
            val a = ae - PI * k / capSteps
            out.add((xs[m - 1] + cos(a) * hs[m - 1]).toFloat()); out.add((ys[m - 1] + sin(a) * hs[m - 1]).toFloat())
        }
        for (i in m - 1 downTo 0) { out.add(xs[i] - nx[i] * hs[i]); out.add(ys[i] - ny[i] * hs[i]) }
        val a0 = atan2(-ny[0].toDouble(), -nx[0].toDouble())
        for (k in 1 until capSteps) {
            val a = a0 - PI * k / capSteps
            out.add((xs[0] + cos(a) * hs[0]).toFloat()); out.add((ys[0] + sin(a) * hs[0]).toFloat())
        }
        return out.toFloatArray()
    }

    // ------------------------------------------------------------------ feels

    /**
     * Shape for a stroke polyline [p] of stroke width [width] under [feel]. [seed] identifies the stroke (+ project
     * seed); [drawing] is the drawing index (only SCRATCH_BOIL changes with it). Returns null for CLEAN / degenerate
     * input: the caller then draws the original polyline.
     */
    fun build(p: FloatArray, width: Float, feel: StrokeFeel, seed: Int, drawing: Int): Shape? {
        if (feel == StrokeFeel.CLEAN || p.size < 2) return null
        val w = max(0.5f, width)
        val len = polyLength(p)
        return when (feel) {
            StrokeFeel.CLEAN -> null
            StrokeFeel.WHITEBOARD_MARKER -> whiteboard(p, w, len, seed)
            StrokeFeel.PEN_PLOTTER -> plotter(p, w)
            StrokeFeel.SCRATCH_BOIL -> boil(p, w, mix(seed, drawing))
        }
    }

    private fun whiteboard(p: FloatArray, w: Float, len: Float, seed: Int): Shape {
        if (p.size < 4 || len < w * 1.2f) {
            return Shape(arrayOf(Line(floatArrayOf(p[0], p[1], p[0], p[1]), 0f, 1f, 1f, 1f)), null, w * 0.6f, p[0], p[1])
        }
        val scale = (w / 9f).coerceIn(0.4f, 3f)
        var q = resample(p, max(2f, w * 0.6f))
        q = wobble(q, 2.6f * scale, 220f * scale, seed)
        if (isClosed(q, w)) q = closureOvershoot(q, w * 0.9f, seed)
        val half = pressureHalfWidths(q, w)
        return Shape(arrayOf(Line(q, 0f, 1f, 1f, 1f)), half, 0f, 0f, 0f)
    }

    private fun plotter(p: FloatArray, w: Float): Shape =
        // Mechanical: no wobble, constant nib, and the pen rests a moment where it lands (a slightly wetter dot).
        Shape(arrayOf(Line(p.copyOf(), 0f, 1f, 1f, 1f)), null, if (w > 1.2f) w * 0.62f else 0f, p[0], p[1])

    private fun boil(p: FloatArray, w: Float, seed: Int): Shape {
        if (p.size < 4) return Shape(arrayOf(Line(floatArrayOf(p[0], p[1], p[0], p[1]), 0f, 1f, 1f, 1f)), null, 0f, 0f, 0f)
        val r = Rng(seed)
        val base = resample(p, (w * 1.5f).coerceIn(2f, 6f))
        val passes = if (w > 1.6f) 3 else 2
        val jit = (w * 0.7).coerceIn(0.25, 5.0)
        val n = base.size / 2
        val lines = ArrayList<Line>()
        for (ps in 0 until passes) {
            val off = if (ps == 0) 0.0 else r.range(-1.0, 1.0) * jit
            val drift = r.range(-1.0, 1.0) * jit * 0.6
            val ph = r.next() * 100
            val alpha = if (ps == 0) 0.95f else (0.5 + r.next() * 0.4).toFloat()
            val wm = if (ps == 0) 1f else 0.45f
            var cur = ArrayList<Float>()
            var curFrom = 0
            fun flush(endIdx: Int) {
                if (cur.size >= 4) lines.add(Line(cur.toFloatArray(), curFrom / (n - 1f), min(1f, endIdx / (n - 1f)), wm, alpha))
                cur = ArrayList()
            }
            var skip = 0
            for (i in 0 until n) {
                val a = max(0, i - 1); val b = min(n - 1, i + 1)
                val tx = (base[2 * b] - base[2 * a]).toDouble(); val ty = (base[2 * b + 1] - base[2 * a + 1]).toDouble()
                val tl = hypot(tx, ty).let { if (it == 0.0) 1.0 else it }
                val u = i.toDouble() / n
                val oo = off + drift * sin(u * 6 + ph) + (r.next() - 0.5) * jit * 0.35
                if (skip > 0) { skip--; if (cur.isNotEmpty()) flush(i) ; continue }
                if (i in 2 until n - 2 && r.next() < 0.012) { skip = 1 + (r.next() * 4).toInt(); flush(i); continue }
                if (cur.isEmpty()) curFrom = i
                cur.add((base[2 * i] - ty / tl * oo).toFloat()); cur.add((base[2 * i + 1] + tx / tl * oo).toFloat())
            }
            flush(n - 1)
        }
        if (lines.isEmpty()) lines.add(Line(base, 0f, 1f, 1f, 1f))
        return Shape(lines.toTypedArray(), null, 0f, 0f, 0f)
    }

    // ------------------------------------------------------------------ cache

    private class Key(val pts: FloatArray, val width: Float, val feel: StrokeFeel, val seed: Int, val drawing: Int) {
        override fun equals(other: Any?): Boolean =
            other is Key && other.pts === pts && other.width == width && other.feel == feel && other.seed == seed && other.drawing == drawing
        override fun hashCode(): Int =
            ((System.identityHashCode(pts) * 31 + width.toBits()) * 31 + feel.ordinal) * 31 + seed * 17 + drawing
    }

    private const val CACHE_MAX = 4096
    private val cache = object : LinkedHashMap<Key, Any>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Any>?) = size > CACHE_MAX
    }
    private val NONE = Any()

    /** Seed for a stroke from its own data and the project seed (stable across frames and exports). */
    fun strokeSeed(p: FloatArray, width: Float, lookSeed: Int): Int {
        val h = if (p.size >= 2) mix(p[0].toBits(), p[1].toBits()) else 0
        return mix(mix(h, width.toBits()), lookSeed)
    }

    /**
     * Cached [build]. Cache key is the points array identity + feel + seeds; for boil the drawing index is part of
     * the key, for other feels it is normalised to 0 so frames reuse the same shape.
     */
    fun shapeFor(p: FloatArray, width: Float, feel: StrokeFeel, lookSeed: Int, drawing: Int): Shape? {
        if (feel == StrokeFeel.CLEAN) return null
        val d = if (feel == StrokeFeel.SCRATCH_BOIL) drawing else 0
        val key = Key(p, width, feel, lookSeed, d)
        synchronized(cache) {
            val hit = cache[key]
            if (hit != null) return if (hit === NONE) null else hit as Shape
        }
        val s = build(p, width, feel, strokeSeed(p, width, lookSeed), d)
        synchronized(cache) { cache[key] = s ?: NONE }
        return s
    }
}
