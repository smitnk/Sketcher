package com.smitnk.sketcher.look

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TipTrackTest {
    private val w = 1000f
    private val h = 700f

    /** Zig-zag over the sheet with a pen lift every 10th of the timeline (pure function of progress). */
    private val zigzag: (Float) -> Tip = { p ->
        val x = ((p * 7f) % 1f).let { if ((p * 7f).toInt() % 2 == 0) it else 1f - it } * w
        val y = p * h
        Tip(x, y, 0, (p * 10f).toInt(), penDown = (p * 10f) % 1f < 0.8f)
    }

    private fun track(dur: Float = 20f, src: (Float) -> Tip = zigzag) = TipTrack.build(dur, src)

    @Test fun tableHasSixHundredSteps() = assertEquals(600, track().steps)

    @Test fun smoothingIsDeterministic() {
        val a = track(); val b = track()
        for (i in 0..1000) {
            val p = i / 1000f
            assertEquals(a.camX(p), b.camX(p), 0f)
            assertEquals(a.camY(p), b.camY(p), 0f)
            assertEquals(a.lift(p), b.lift(p), 0f)
            assertEquals(a.headingX(p), b.headingX(p), 0f)
        }
        // querying in a different order (scrubbing backwards) gives the same values
        val fwd = (0..100).map { a.camX(it / 100f) }
        val bwd = (100 downTo 0).map { a.camX(it / 100f) }.reversed()
        assertEquals(fwd, bwd)
    }

    @Test fun gaussianKeepsConstantAndIsNonCausal() {
        val c = TipTrack.gaussianSmooth(FloatArray(50) { 5f }, 4f)
        for (v in c) assertEquals(5f, v, 1e-4f)
        // a step at index 100: smoothed signal has already left 0 well before it (looks ahead) and is not yet 1 after it
        val step = FloatArray(200) { if (it >= 100) 1f else 0f }
        val s = TipTrack.gaussianSmooth(step, 8f)
        assertTrue(s[95] > 0.01f)
        assertTrue(s[105] < 0.99f)
        assertEquals(0.5f, s[100], 0.1f)
        assertEquals(0f, TipTrack.gaussianSmooth(step, 0f)[99], 0f)
    }

    @Test fun cameraPathIsSmootherThanTip() {
        val jump: (Float) -> Tip = { p -> Tip(if (p < 0.5f) 100f else 900f, 300f, 0, 0, true) } // pen-up teleport
        val t = track(20f, jump)
        var rawMax = 0f; var camMax = 0f
        for (i in 1..2000) {
            val p0 = (i - 1) / 2000f; val p1 = i / 2000f
            rawMax = maxOf(rawMax, abs(jump(p1).x - jump(p0).x))
            camMax = maxOf(camMax, abs(t.camX(p1) - t.camX(p0)))
        }
        assertTrue("cam step $camMax should be well under raw step $rawMax", camMax < rawMax / 3f)
    }

    @Test fun penUpShowsAsLift() {
        val t = track()
        assertTrue(t.lift(0.09f) > 0.5f)   // inside the 20% lifted part of a tenth
        assertTrue(t.lift(0.04f) < 0.1f)
    }

    @Test fun cameraWindowStaysInsideSheet() {
        for (dur in listOf(2f, 5f, 20f, 60f)) {
            val t = track(dur)
            for (zoom in listOf(1f, 1.4f, 1.6f, 2.5f, 9f)) for (strength in listOf(0f, 0.5f, 1f)) {
                for (i in 0..300) {
                    val c = CameraFollow.transform(t, i / 300f, dur, w, h, zoom, strength)
                    val vw = w / c.zoom; val vh = h / c.zoom
                    assertTrue(c.zoom in 1f..2.5f)
                    assertTrue("x0 ${c.originX}", c.originX >= -1e-3f)
                    assertTrue("y0 ${c.originY}", c.originY >= -1e-3f)
                    assertTrue(c.originX + vw <= w + 1e-2f)
                    assertTrue(c.originY + vh <= h + 1e-2f)
                }
            }
        }
    }

    @Test fun clampsAtCornersAndCentresWhenFree() {
        val corner = track(20f) { Tip(0f, 0f, 0, 0, true) }
        val c = CameraFollow.transform(corner, 0.5f, 20f, w, h, 2f)
        assertEquals(2f, c.zoom, 1e-4f); assertEquals(0f, c.tx, 1e-3f); assertEquals(0f, c.ty, 1e-3f)
        val mid = track(20f) { Tip(w / 2, h / 2, 0, 0, true) }
        val m = CameraFollow.transform(mid, 0.5f, 20f, w, h, 2f)
        assertEquals(w / 4f, m.originX, 1e-2f); assertEquals(h / 4f, m.originY, 1e-2f)
        // the pen stays inside the window and at its centre when not clamped
        val tipOnScreenX = (w / 2 * m.zoom) + m.tx
        assertEquals(w / 2f, tipOnScreenX, 1e-2f)
    }

    @Test fun startAndEndShowTheWholeDrawing() {
        for (dur in listOf(2f, 8f, 20f, 60f)) {
            val t = track(dur)
            assertTrue(CameraFollow.transform(t, 0f, dur, w, h, 2.5f).isIdentity)
            assertTrue(CameraFollow.transform(t, 1f, dur, w, h, 2.5f).isIdentity)
            // mid-animation is zoomed, and the ease-out is monotone over the last 1.5 s
            assertTrue(CameraFollow.transform(t, 0.5f, dur, w, h, 2.5f).zoom > 2.4f)
            val tout = minOf(1.5f, dur * 0.35f)
            var prev = Float.MAX_VALUE
            for (i in 0..50) {
                val p = 1f - (tout / dur) * (1f - i / 50f)
                val z = CameraFollow.transform(t, minOf(p, 1f), dur, w, h, 2.5f).zoom
                assertTrue(z <= prev + 1e-5f); prev = z
            }
            assertFalse(CameraFollow.transform(t, 1f - 0.25f / dur, dur, w, h, 2.5f).isIdentity)
        }
    }

    @Test fun nullTrackOrOffIsIdentity() {
        assertTrue(CameraFollow.transform(null, 0.5f, 20f, w, h, 2f).isIdentity)
    }

    @Test fun penFadesOutAtTheEnd() {
        assertEquals(0f, PenOverlay.alpha(1f, 20f), 0f)
        assertEquals(0f, PenOverlay.alpha(0f, 20f), 0f)
        assertEquals(1f, PenOverlay.alpha(0.5f, 20f), 0f)
    }
}
