package com.smitnk.sketcher.anim

/**
 * Where the drawing hand's pen tip is at a given animation progress (see docs/wiring/tip.md).
 * [layerIndex] is the index into the layer list the caller passed (-1 = none); [strokeIndex] the stroke inside that layer
 * (pencil-reveal layers: the source stroke, not the piece). [x], [y] are image px. [speed] is image px per real second.
 * Mutable so a frame loop can reuse one instance; [layerId] is the stable id of the layer.
 */
class TipState(
    var x: Float = 0f,
    var y: Float = 0f,
    var layerIndex: Int = -1,
    var strokeIndex: Int = -1,
    var penDown: Boolean = false,
    var speed: Float = 0f,
    var layerId: Long = -1L,
) {
    internal fun set(x: Float, y: Float, layerIndex: Int, strokeIndex: Int, penDown: Boolean, speed: Float, layerId: Long) {
        this.x = x; this.y = y; this.layerIndex = layerIndex; this.strokeIndex = strokeIndex
        this.penDown = penDown; this.speed = speed; this.layerId = layerId
    }
}

/** Pure queries (no android types; for project layers use [LayerTimeline.tipAt]) on the same [DrawTimeline.Timeline] that drives [AnimationController.allowances]. Binary search, no allocation with a reused [TipState]. */
object TimelineQuery {
    /**
     * Tip on [timeline] at [progress] (0..1, as seen by the viewer: pass [LookTiming.effectiveProgress] to match onTwos).
     * [TipState.layerIndex] here is the timeline's play-order layer index; use the [layers] overload for project indices.
     */
    fun tipAt(timeline: DrawTimeline.Timeline, progress: Float, durationSec: Float = AnimationController.DEFAULT_DURATION_SEC, out: TipState = TipState()): TipState {
        timeline.tipAt(progress, durationSec, out)
        return out
    }
}
