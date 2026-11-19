package com.smitnk.sketcher.anim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineQueryTest {
    /** Layer 7: two horizontal 100 px strokes at y=0 and y=50; layer 9: one vertical stroke. */
    private fun timeline(): DrawTimeline.Timeline {
        val a = DrawTimeline.LayerSpec(
            7, floatArrayOf(100f, 100f), floatArrayOf(0f, 0f), floatArrayOf(0f, 50f), floatArrayOf(100f, 100f), floatArrayOf(0f, 50f),
            floatArrayOf(1f, 1f), points = arrayOf(floatArrayOf(0f, 0f, 100f, 0f), floatArrayOf(0f, 50f, 100f, 50f)),
        )
        val b = DrawTimeline.LayerSpec(
            9, floatArrayOf(60f), floatArrayOf(10f), floatArrayOf(0f), floatArrayOf(10f), floatArrayOf(60f),
            floatArrayOf(1f), points = arrayOf(floatArrayOf(10f, 0f, 10f, 60f)),
        )
        return DrawTimeline.build(listOf(a, b))
    }

    @Test fun restsAtEnds() {
        val t = timeline(); val s = TipState()
        TimelineQuery.tipAt(t, 0f, 10f, s)
        assertEquals(0f, s.x, 0f); assertEquals(0f, s.y, 0f) // p = 0: the pen is at the first stroke's start
        TimelineQuery.tipAt(t, 1f, 10f, s)
        assertEquals(10f, s.x, 0f); assertEquals(60f, s.y, 0f); assertFalse(s.penDown); assertEquals(0f, s.speed, 0f)
        assertEquals(9L, s.layerId); assertEquals(0, s.strokeIndex)
    }

    @Test fun tipMatchesAllowanceAndStrokeIdentity() {
        val t = timeline(); val s = TipState()
        val a = Allowances()
        var down = 0
        for (i in 1 until 1000) {
            val p = i / 1000f
            TimelineQuery.tipAt(t, p, 10f, s)
            t.fillAllowances(p, a)
            if (s.penDown) {
                down++
                assertTrue(s.speed >= 0f)
                if (s.layerId == 7L) {
                    // x along the horizontal stroke equals the allowance of the layer minus earlier strokes
                    val shown = a.budget(7)
                    val expectX = if (s.strokeIndex == 0) shown else shown - 100f
                    assertEquals(expectX, s.x, 0.01f)
                    assertEquals(if (s.strokeIndex == 0) 0f else 50f, s.y, 0f)
                }
            }
        }
        assertTrue(down > 400)
    }

    @Test fun gapBetweenStrokesIsPenUpTravelToNextStart() {
        val t = timeline(); val s = TipState()
        val mid = (t.endOf(0) + t.startOf(1)) / 2
        assertTrue(t.startOf(1) > t.endOf(0))
        TimelineQuery.tipAt(t, mid.toFloat(), 10f, s)
        assertFalse(s.penDown)
        assertEquals(1, s.strokeIndex)
        assertEquals(50f, s.x, 0.5f)   // halfway from (100,0) to (0,50)
        assertEquals(25f, s.y, 0.5f)
        assertTrue(s.speed > 0f)
    }

    @Test fun emptyTimeline() {
        val s = TipState()
        TimelineQuery.tipAt(DrawTimeline.build(emptyList()), 0.5f, 10f, s)
        assertEquals(-1, s.layerIndex)
    }
}
