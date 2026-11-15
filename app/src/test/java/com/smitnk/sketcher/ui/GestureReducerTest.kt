package com.smitnk.sketcher.ui

import com.smitnk.sketcher.ui.canvas.Gesture
import com.smitnk.sketcher.ui.canvas.GestureAction
import com.smitnk.sketcher.ui.canvas.GestureEvent
import com.smitnk.sketcher.ui.canvas.GestureReducer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureReducerTest {
    private val slop = 20f
    private fun r(g: Gesture, e: GestureEvent) = GestureReducer.reduce(g, e, slop)
    private val pending = Gesture.Pending(100f, 100f, 0L)

    @Test fun downStartsPending() {
        assertEquals(pending, r(Gesture.Idle, GestureEvent.Down(100f, 100f, 0L)).first)
    }

    @Test fun smallQuickMoveStaysPending() {
        val (g, a) = r(pending, GestureEvent.Move(1, 105f, 100f, 30L))
        assertEquals(pending, g); assertEquals(GestureAction.NONE, a)
    }

    @Test fun slopCommitsStroke() {
        val (g, a) = r(pending, GestureEvent.Move(1, 130f, 100f, 30L))
        assertTrue(g is Gesture.Stroke); assertEquals(GestureAction.BEGIN_STROKE, a)
    }

    @Test fun timeCommitsStroke() {
        val (g, a) = r(pending, GestureEvent.Move(1, 101f, 100f, 80L))
        assertTrue(g is Gesture.Stroke); assertEquals(GestureAction.BEGIN_STROKE, a)
        assertTrue(r(pending, GestureEvent.Tick(80L)).first is Gesture.Stroke)
        assertEquals(pending, r(pending, GestureEvent.Tick(40L)).first)
    }

    @Test fun secondPointerWhilePendingIsTransformNoStroke() {
        val (g, a) = r(pending, GestureEvent.Move(2, 105f, 100f, 10L))
        assertEquals(Gesture.Transform, g); assertEquals(GestureAction.TRANSFORM, a)
    }

    @Test fun secondPointerDuringStrokeCancelsThenTransform() {
        val (g, a) = r(Gesture.Stroke(), GestureEvent.Move(2, 0f, 0f, 200L))
        assertEquals(Gesture.Transform, g); assertEquals(GestureAction.CANCEL_STROKE_TO_TRANSFORM, a)
    }

    @Test fun strokeExtendsAndEnds() {
        assertEquals(GestureAction.EXTEND_STROKE, r(Gesture.Stroke(), GestureEvent.Move(1, 0f, 0f, 200L)).second)
        assertEquals(GestureAction.END_STROKE, r(Gesture.Stroke(), GestureEvent.Up).second)
    }

    @Test fun blockedStrokeDoesNothing() {
        val b = Gesture.Stroke(blocked = true)
        assertEquals(GestureAction.NONE, r(b, GestureEvent.Move(1, 0f, 0f, 200L)).second)
        assertEquals(GestureAction.NONE, r(b, GestureEvent.Up).second)
        assertEquals(GestureAction.NONE, r(b, GestureEvent.Cancel).second)
    }

    @Test fun tapDrawsDot() {
        val (g, a) = r(pending, GestureEvent.Up)
        assertEquals(Gesture.Idle, g); assertEquals(GestureAction.TAP_STROKE, a)
    }

    @Test fun cancelDuringStrokeDiscards() {
        assertEquals(GestureAction.CANCEL_STROKE, r(Gesture.Stroke(), GestureEvent.Cancel).second)
        assertEquals(GestureAction.NONE, r(pending, GestureEvent.Cancel).second)
    }

    @Test fun transformKeepsTransformingWithOneFingerLeft() {
        assertEquals(GestureAction.TRANSFORM, r(Gesture.Transform, GestureEvent.Move(1, 0f, 0f, 0L)).second)
        assertEquals(Gesture.Idle, r(Gesture.Transform, GestureEvent.Up).first)
    }
}
