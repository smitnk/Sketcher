package com.smitnk.sketcher.image.pipeline

import com.smitnk.sketcher.image.Stroke
import kotlin.math.ceil
import kotlin.math.hypot

/** Splits background strokes from the character: pure JVM, no Android. */
object BackgroundSplit {

    /**
     * Subject (character) pixels, true = character. Uses the detected backdrop mask when there is one, else the subject
     * rectangle. Null when nothing is background (ordinary full-frame photo).
     */
    fun subjectMask(sub: SubjectCrop.Result): BooleanArray? {
        val w = sub.w; val h = sub.h
        sub.bgMask?.let { bg -> return BooleanArray(w * h) { !bg[it] } }
        if (sub.isFull) return null
        return BooleanArray(w * h) { val x = it % w; val y = it / w; x >= sub.x0 && x < sub.x1 && y >= sub.y0 && y < sub.y1 }
    }

    /** [mask] grown by [pad] px (square). */
    internal fun dilate(mask: BooleanArray, w: Int, h: Int, pad: Int): BooleanArray {
        if (pad <= 0) return mask.copyOf()
        val tmp = BooleanArray(w * h)
        val pre = IntArray(maxOf(w, h) + 1)
        for (y in 0 until h) {
            for (x in 0 until w) pre[x + 1] = pre[x] + if (mask[y * w + x]) 1 else 0
            for (x in 0 until w) tmp[y * w + x] = pre[minOf(w, x + pad + 1)] - pre[maxOf(0, x - pad)] > 0
        }
        val out = BooleanArray(w * h)
        for (x in 0 until w) {
            for (y in 0 until h) pre[y + 1] = pre[y] + if (tmp[y * w + x]) 1 else 0
            for (y in 0 until h) out[y * w + x] = pre[minOf(h, y + pad + 1)] - pre[maxOf(0, y - pad)] > 0
        }
        return out
    }

    /**
     * Cuts every stroke so no part of it (including its half width) touches a [subject] pixel; pieces shorter than two
     * points are dropped. Strokes are in [w]x[h] pixel coordinates.
     */
    fun clip(strokes: List<Stroke>, subject: BooleanArray, w: Int, h: Int): List<Stroke> {
        if (strokes.isEmpty() || w <= 0 || h <= 0) return emptyList()
        val pad = ceil(strokes.maxOf { it.width } / 2f).toInt() + 1
        val block = dilate(subject, w, h, pad)
        fun hit(x: Float, y: Float): Boolean = block[y.toInt().coerceIn(0, h - 1) * w + x.toInt().coerceIn(0, w - 1)]
        val out = ArrayList<Stroke>()
        var buf = FloatArray(64)
        for (s in strokes) {
            val p = s.points
            if (p.size < 2) continue
            if (p.size == 2) { if (!hit(p[0], p[1])) out.add(s); continue }
            var n = 0
            fun flush() { if (n >= 4) out.add(s.copy(points = buf.copyOf(n))); n = 0 }
            fun push(x: Float, y: Float) { if (n + 2 > buf.size) buf = buf.copyOf(buf.size * 2); buf[n++] = x; buf[n++] = y }
            if (!hit(p[0], p[1])) push(p[0], p[1])
            var i = 2
            while (i + 1 < p.size) {
                val ax = p[i - 2]; val ay = p[i - 1]; val bx = p[i]; val by = p[i + 1]
                val steps = maxOf(1, ceil(hypot(bx - ax, by - ay)).toInt())
                var clean = true
                for (k in 0..steps) { val t = k.toFloat() / steps; if (hit(ax + (bx - ax) * t, ay + (by - ay) * t)) { clean = false; break } }
                if (clean) { if (n == 0) push(ax, ay); push(bx, by) }
                else { flush(); if (!hit(bx, by)) push(bx, by) }
                i += 2
            }
            flush()
        }
        return out
    }
}
