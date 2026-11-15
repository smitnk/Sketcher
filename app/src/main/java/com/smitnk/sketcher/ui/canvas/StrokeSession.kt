package com.smitnk.sketcher.ui.canvas

import android.graphics.Bitmap
import com.smitnk.sketcher.image.BrushType
import com.smitnk.sketcher.image.Stroke
import com.smitnk.sketcher.ui.Tool

/**
 * One in-progress stroke/erase. Tool, brush, colour, size and alpha are frozen at construction so changing
 * them mid-gesture never affects it; the target layer is held by id and looked up again when the stroke ends.
 */
class StrokeSession(
    val layerId: Long,
    val tool: Tool,
    val brushType: BrushType,
    val color: Int,
    val size: Float,
    val alpha: Float,
    /** Copy of the ink texture before an eraser stroke on a pencil-reveal layer (for undo / cancel). */
    val inkBefore: Bitmap? = null,
) {
    var points = FloatArray(256)
        private set
    var count = 0
        private set

    /** (index, stroke) removed by a vector erase, in removal order. */
    val erased = ArrayList<Pair<Int, Stroke>>()
    var inkErased = false
    var lastEraseX = Float.NaN
    var lastEraseY = 0f

    fun addPoint(x: Float, y: Float) {
        if (count > 0) {
            val dx = x - points[(count - 1) * 2]
            val dy = y - points[(count - 1) * 2 + 1]
            if (dx * dx + dy * dy < 0.25f) return
        }
        if ((count + 1) * 2 > points.size) points = points.copyOf(points.size * 2)
        points[count * 2] = x
        points[count * 2 + 1] = y
        count++
    }

    /** The finished brush stroke, or null when nothing was drawn. A single point becomes a dot. */
    fun toStroke(): Stroke? {
        if (count == 0) return null
        if (count == 1) {
            points[2] = points[0] + 0.01f
            points[3] = points[1]
            count = 2
        }
        return Stroke(points.copyOf(count * 2), color, size, brushType, alpha)
    }

    /** Preview of the live brush stroke. */
    fun liveStroke(): Stroke? {
        if (tool != Tool.BRUSH || count == 0) return null
        val pts = if (count == 1) {
            floatArrayOf(points[0], points[1], points[0] + 0.01f, points[1])
        } else {
            points.copyOf(count * 2)
        }
        return Stroke(pts, color, size, brushType, alpha)
    }
}
