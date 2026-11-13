package com.smitnk.sketcher.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.dp
import com.smitnk.sketcher.export.Renderer
import com.smitnk.sketcher.ui.SketchViewModel
import com.smitnk.sketcher.ui.theme.SketcherTokens
import kotlin.math.min

/** View transform: zoom 1 = drawing fits the screen, pan in screen pixels. */
private class ViewTransform {
    var zoom by mutableFloatStateOf(1f)
    var pan by mutableStateOf(Offset.Zero)

    /** Space reserved for floating chrome (px); the paper is fitted and centred in what remains. */
    var insetTop by mutableFloatStateOf(0f)
    var insetBottom by mutableFloatStateOf(0f)
    var margin by mutableFloatStateOf(0f)

    fun scale(cw: Float, ch: Float, pw: Int, ph: Int): Float =
        min(
            ((cw - 2f * margin) / pw).coerceAtLeast(0.01f),
            ((ch - insetTop - insetBottom - 2f * margin) / ph).coerceAtLeast(0.01f),
        ) * zoom

    fun base(cw: Float, ch: Float, pw: Int, ph: Int, s: Float): Offset =
        Offset((cw - pw * s) / 2f, insetTop + (ch - insetTop - insetBottom - ph * s) / 2f)

    /** Screen position of image point (0,0). */
    fun origin(cw: Float, ch: Float, pw: Int, ph: Int, s: Float): Offset = base(cw, ch, pw, ph, s) + pan

    fun reset() {
        zoom = 1f
        pan = Offset.Zero
    }
}

@Composable
fun SketchCanvas(
    vm: SketchViewModel,
    modifier: Modifier = Modifier,
    insetTopPx: Float = 0f,
    insetBottomPx: Float = 0f,
) {
    val project = vm.project
    val vt = remember { ViewTransform() }
    vt.insetTop = insetTopPx
    vt.insetBottom = insetBottomPx
    vt.margin = with(androidx.compose.ui.platform.LocalDensity.current) { SketcherTokens.space12.toPx() }
    val paperPaint = remember { android.graphics.Paint() }
    val paperColor = com.smitnk.sketcher.ui.theme.LightExtraColors.paper.toArgb() // always light paper, both themes
    val layerCache = remember { Renderer.LayerCache() }
    DisposableEffect(layerCache) { onDispose { layerCache.release() } }

    LaunchedEffect(project.width, project.height) { vt.reset() }

    Canvas(
        modifier = modifier
            .clipToBounds()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val cw = size.width.toFloat()
                    val ch = size.height.toFloat()

                    fun toImage(p: Offset): Offset {
                        val s = vt.scale(cw, ch, project.width, project.height)
                        val o = vt.origin(cw, ch, project.width, project.height, s)
                        return (p - o) / s
                    }

                    val start = toImage(down.position)
                    var drawing = vm.beginStroke(start.x, start.y)

                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (drawing && pressed >= 2) {
                            vm.endStroke(cancel = true)
                            drawing = false
                        }
                        if (drawing) {
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change != null && change.pressed) {
                                val p = toImage(change.position)
                                vm.extendStroke(p.x, p.y)
                                change.consume()
                            }
                        } else if (pressed > 0) {
                            // pinch-zoom and pan around the finger centroid
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = true)
                            val pw = project.width
                            val ph = project.height
                            val oldS = vt.scale(cw, ch, pw, ph)
                            val oldO = vt.origin(cw, ch, pw, ph, oldS)
                            val q = ((centroid - panChange) - oldO) / oldS
                            vt.zoom = (vt.zoom * zoomChange).coerceIn(0.5f, 16f)
                            val newS = vt.scale(cw, ch, pw, ph)
                            val newO = centroid - q * newS
                            vt.pan = newO - vt.base(cw, ch, pw, ph, newS)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })

                    if (drawing) vm.endStroke(cancel = false)
                }
            },
    ) {
        val pw = project.width
        val ph = project.height
        val s = vt.scale(size.width, size.height, pw, ph)
        val o = vt.origin(size.width, size.height, pw, ph, s)
        @Suppress("UNUSED_VARIABLE")
        val tick = vm.liveTick // subscribe to live stroke changes
        val allowances = vm.anim.allowances(project.layers)
        val live = vm.liveStroke()
        val liveLayerId = vm.liveLayerId

        // Soft shadow under the sheet (stacked translucent rounded rects; works on every API level).
        val sheetSize = Size(pw * s, ph * s)
        val steps = 8
        for (i in steps downTo 1) {
            val g = i * 1.5.dp.toPx()
            drawRoundRect(
                color = Color.Black.copy(alpha = 0.028f),
                topLeft = Offset(o.x - g, o.y - g + 3.dp.toPx()),
                size = Size(sheetSize.width + 2 * g, sheetSize.height + 2 * g),
                cornerRadius = CornerRadius(g + 2.dp.toPx()),
            )
        }
        paperPaint.color = paperColor

        drawIntoCanvas { canvas ->
            val c = canvas.nativeCanvas
            // Integer sheet origin so the cached layer bitmap maps 1:1 to device pixels.
            val ox = Math.round(o.x).toFloat()
            val oy = Math.round(o.y).toFloat()
            c.save()
            c.translate(ox, oy)
            c.save()
            c.scale(s, s)
            c.drawRect(0f, 0f, pw.toFloat(), ph.toFloat(), paperPaint)
            c.clipRect(0f, 0f, pw.toFloat(), ph.toFloat())
            Renderer.drawBackground(c, project)
            c.restore()
            layerCache.draw(c, s, pw, ph, project.layers, allowances, live, liveLayerId)
            c.restore()
        }
    }
}
