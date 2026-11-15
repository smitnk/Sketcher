package com.smitnk.sketcher.ui.canvas

import kotlin.math.sqrt

/** Pure canvas-gesture state machine (no Android/Compose types so it is JVM-testable). */
sealed class Gesture {
    object Idle : Gesture()

    /** One finger is down but has not yet moved past slop / waited long enough to be a stroke. */
    data class Pending(val x: Float, val y: Float, val downTimeMs: Long) : Gesture()

    /** A stroke is being drawn. [blocked] = the layer refused it (locked/hidden/texture): nothing is drawn. */
    data class Stroke(val blocked: Boolean = false) : Gesture()

    /** Pinch-zoom / pan. */
    object Transform : Gesture()
}

sealed class GestureEvent {
    data class Down(val x: Float, val y: Float, val timeMs: Long) : GestureEvent()

    /** [pressed] = number of fingers currently down; x,y = the primary finger. */
    data class Move(val pressed: Int, val x: Float, val y: Float, val timeMs: Long) : GestureEvent()

    /** Commit timer fired with no new input. */
    data class Tick(val timeMs: Long) : GestureEvent()

    /** All fingers lifted. */
    object Up : GestureEvent()

    /** Gesture cancelled (dispose, parent took over). */
    object Cancel : GestureEvent()
}

enum class GestureAction {
    NONE,

    /** Begin a stroke at the Pending down position, then extend to the current position. */
    BEGIN_STROKE,

    /** Finger lifted while still Pending: draw a dot (begin + end at the down position). */
    TAP_STROKE,
    EXTEND_STROKE,
    END_STROKE,

    /** Discard the live stroke without an undo entry; the gesture is now a Transform. */
    CANCEL_STROKE_TO_TRANSFORM,

    /** Cancelled outside a pinch (dispose): discard the live stroke. */
    CANCEL_STROKE,
    TRANSFORM,
}

object GestureReducer {
    const val COMMIT_DELAY_MS = 80L

    fun reduce(g: Gesture, e: GestureEvent, slopPx: Float): Pair<Gesture, GestureAction> = when (e) {
        is GestureEvent.Down -> when (g) {
            Gesture.Idle -> Gesture.Pending(e.x, e.y, e.timeMs) to GestureAction.NONE
            else -> g to GestureAction.NONE
        }
        is GestureEvent.Move -> when (g) {
            is Gesture.Pending -> when {
                e.pressed >= 2 -> Gesture.Transform to GestureAction.TRANSFORM
                e.pressed == 0 -> g to GestureAction.NONE
                dist(g.x, g.y, e.x, e.y) >= slopPx || e.timeMs - g.downTimeMs >= COMMIT_DELAY_MS ->
                    Gesture.Stroke() to GestureAction.BEGIN_STROKE
                else -> g to GestureAction.NONE
            }
            is Gesture.Stroke -> when {
                e.pressed >= 2 -> Gesture.Transform to GestureAction.CANCEL_STROKE_TO_TRANSFORM
                e.pressed == 1 && !g.blocked -> g to GestureAction.EXTEND_STROKE
                else -> g to GestureAction.NONE
            }
            Gesture.Transform -> if (e.pressed > 0) g to GestureAction.TRANSFORM else g to GestureAction.NONE
            Gesture.Idle -> g to GestureAction.NONE
        }
        is GestureEvent.Tick -> when {
            g is Gesture.Pending && e.timeMs - g.downTimeMs >= COMMIT_DELAY_MS ->
                Gesture.Stroke() to GestureAction.BEGIN_STROKE
            else -> g to GestureAction.NONE
        }
        GestureEvent.Up -> when (g) {
            is Gesture.Pending -> Gesture.Idle to GestureAction.TAP_STROKE
            is Gesture.Stroke -> Gesture.Idle to if (g.blocked) GestureAction.NONE else GestureAction.END_STROKE
            else -> Gesture.Idle to GestureAction.NONE
        }
        GestureEvent.Cancel -> when (g) {
            is Gesture.Stroke -> Gesture.Idle to if (g.blocked) GestureAction.NONE else GestureAction.CANCEL_STROKE
            else -> Gesture.Idle to GestureAction.NONE
        }
    }

    private fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        return sqrt(dx * dx + dy * dy)
    }
}
