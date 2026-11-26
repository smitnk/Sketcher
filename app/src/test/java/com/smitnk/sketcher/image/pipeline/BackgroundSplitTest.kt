package com.smitnk.sketcher.image.pipeline

import com.smitnk.sketcher.image.Stroke
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.random.Random

class BackgroundSplitTest {
    private val w = 120
    private val h = 90

    /** Disc-shaped character in the middle. */
    private val subject = BooleanArray(w * h) { val x = it % w; val y = it / w; hypot(x - 60f, y - 45f) < 25f }

    /** True when any pixel covered by the stroke (its half width included) is a character pixel. */
    private fun touchesSubject(s: Stroke): Boolean {
        val r = ceil(s.width / 2f).toInt()
        val p = s.points
        var i = 0
        while (i + 1 < p.size) {
            val ax = p[maxOf(0, i - 2)]; val ay = p[maxOf(1, i - 1)]; val bx = p[i]; val by = p[i + 1]
            val steps = maxOf(1, ceil(hypot(bx - ax, by - ay)).toInt())
            for (k in 0..steps) {
                val t = k.toFloat() / steps
                val cx = (ax + (bx - ax) * t).toInt(); val cy = (ay + (by - ay) * t).toInt()
                for (dy in -r..r) for (dx in -r..r) {
                    val x = cx + dx; val y = cy + dy
                    if (x in 0 until w && y in 0 until h && subject[y * w + x]) return true
                }
            }
            i += 2
        }
        return false
    }

    @Test fun noBackgroundStrokeTouchesTheCharacter() {
        val rnd = Random(7)
        val strokes = List(300) {
            val n = 2 + rnd.nextInt(8)
            Stroke(FloatArray(n * 2) { k -> if (k % 2 == 0) rnd.nextFloat() * w else rnd.nextFloat() * h }, 0xFF000000.toInt(), 1f + rnd.nextFloat() * 5f)
        }
        val out = BackgroundSplit.clip(strokes, subject, w, h)
        assertTrue(out.isNotEmpty())
        for (s in out) assertFalse(touchesSubject(s))
    }

    @Test fun strokesFarFromCharacterAreKept() {
        val s = Stroke(floatArrayOf(2f, 2f, 20f, 2f, 20f, 10f), 0, 2f)
        val out = BackgroundSplit.clip(listOf(s), subject, w, h)
        assertEquals(1, out.size); assertArrayEquals(s.points, out[0].points, 0f)
    }

    @Test fun strokeThroughCharacterIsCutInTwo() {
        val s = Stroke(floatArrayOf(2f, 45f, 10f, 45f, 20f, 45f, 60f, 45f, 100f, 45f, 110f, 45f, 118f, 45f), 0, 2f)
        val out = BackgroundSplit.clip(listOf(s), subject, w, h)
        assertEquals(2, out.size)
        for (o in out) assertFalse(touchesSubject(o))
    }

    @Test fun subjectMaskFromCrop() {
        assertNull(BackgroundSplit.subjectMask(SubjectCrop.full(10, 10)))
        val bg = BooleanArray(100) { it < 50 }
        val m = BackgroundSplit.subjectMask(SubjectCrop.Result(10, 10, 0, 0, 10, 10, bg, 0.5f))!!
        assertTrue((0 until 100).all { m[it] == !bg[it] })
        val r = BackgroundSplit.subjectMask(SubjectCrop.Result(10, 10, 2, 2, 5, 5, null, 0f))!!
        assertTrue(r[3 * 10 + 3]); assertFalse(r[0]); assertFalse(r[5 * 10 + 5])
    }
}
