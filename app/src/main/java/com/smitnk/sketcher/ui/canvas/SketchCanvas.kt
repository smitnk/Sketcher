package com.smitnk.sketcher.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.dp
import com.smitnk.sketcher.export.Renderer
import com.smitnk.sketcher.look.CameraFollow
import com.smitnk.sketcher.look.MotionFx
import com.smitnk.sketcher.look.PaperRender
import com.smitnk.sketcher.look.applyTo
import com.smitnk.sketcher.look.PostFilter
import com.smitnk.sketcher.ui.SketchViewModel
import com.smitnk.sketcher.ui.Tool
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
    /** True while a size slider is being dragged: shows the size circle at the middle of the sheet. */
    sizePreview: Boolean = false,
) {
    val project = vm.project
    val vt = remember { ViewTransform() }
    var eraseTouch by remember { mutableStateOf<Offset?>(null) } // finger position while erasing (screen px)
    vt.insetTop = insetTopPx
    vt.insetBottom = insetBottomPx
    vt.margin = with(androidx.compose.ui.platform.LocalDensity.current) { SketcherTokens.space12.toPx() }
    val paperPaint = remember { android.graphics.Paint() }
    val paperColor = com.smitnk.sketcher.ui.theme.LightExtraColors.paper.toArgb() // always light paper, both themes
    val layerCache = remember { Renderer.LayerCache() }
    DisposableEffect(layerCache) { onDispose { layerCache.release() } }
    val postPreview = remember { PostPreviewCache() }
    DisposableEffect(postPreview) { onDispose { postPreview.release() } }
    val onionCache = remember { OnionCache() }
    DisposableEffect(onionCache) { onDispose { onionCache.release() } }
    val onionPrevTint = MaterialTheme.colorScheme.primary.toArgb() // vermilion: previous frame
    val onionNextTint = MaterialTheme.colorScheme.secondary.toArgb() // graphite blue: next frame

    LaunchedEffect(vm.projectEpoch, project.width, project.height) { vt.reset() }

    Canvas(
        modifier = modifier
            .clipToBounds()
            .pointerInput(Unit) {
                val slopPx = 8.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val cw = size.width.toFloat()
                    val ch = size.height.toFloat()

                    fun toImage(p: Offset): Offset {
                        val s = vt.scale(cw, ch, vm.project.width, vm.project.height)
                        val o = vt.origin(cw, ch, vm.project.width, vm.project.height, s)
                        return (p - o) / s
                    }

                    var gesture: Gesture = GestureReducer.reduce(
                        Gesture.Idle,
                        GestureEvent.Down(down.position.x, down.position.y, down.uptimeMillis),
                        slopPx,
                    ).first
                    var session: StrokeSession? = null

                    fun perform(action: GestureAction, x: Float, y: Float) {
                        when (action) {
                            GestureAction.BEGIN_STROKE, GestureAction.TAP_STROKE -> {
                                val start = toImage(down.position)
                                session = vm.beginStroke(start.x, start.y)
                                if (session == null) {
                                    // beginStroke already toasted: once per gesture, then nothing is drawn or panned
                                    gesture = Gesture.Stroke(blocked = true)
                                } else if (action == GestureAction.BEGIN_STROKE) {
                                    val p = toImage(Offset(x, y))
                                    vm.extendStroke(session!!, p.x, p.y)
                                } else {
                                    vm.endStroke(session!!)
                                    session = null
                                }
                            }
                            GestureAction.EXTEND_STROKE -> session?.let {
                                val p = toImage(Offset(x, y))
                                vm.extendStroke(it, p.x, p.y)
                            }
                            GestureAction.END_STROKE -> session?.let { vm.endStroke(it); session = null }
                            GestureAction.CANCEL_STROKE, GestureAction.CANCEL_STROKE_TO_TRANSFORM ->
                                session?.let { vm.cancelStroke(it); session = null }
                            else -> {}
                        }
                    }

                    try {
                        do {
                            val pending = gesture as? Gesture.Pending
                            val event = if (pending != null) {
                                val wait = GestureReducer.COMMIT_DELAY_MS - (down.uptimeMillis - pending.downTimeMs)
                                withTimeoutOrNull(wait.coerceAtLeast(1L)) { awaitPointerEvent() }
                            } else {
                                awaitPointerEvent()
                            }
                            if (event == null) {
                                // Held still for the commit delay: this is a stroke.
                                val (g, a) = GestureReducer.reduce(
                                    gesture,
                                    GestureEvent.Tick(pending!!.downTimeMs + GestureReducer.COMMIT_DELAY_MS),
                                    slopPx,
                                )
                                gesture = g
                                perform(a, pending.x, pending.y)
                                continue
                            }
                            val pressed = event.changes.count { it.pressed }
                            val primary = event.changes.firstOrNull { it.id == down.id && it.pressed }
                                ?: event.changes.firstOrNull { it.pressed }
                            val pos = primary?.position ?: down.position
                            val time = event.changes.firstOrNull()?.uptimeMillis ?: down.uptimeMillis
                            val (g, action) = GestureReducer.reduce(
                                gesture,
                                GestureEvent.Move(pressed, pos.x, pos.y, time),
                                slopPx,
                            )
                            gesture = g
                            perform(action, pos.x, pos.y)
                            eraseTouch = if (vm.tool == Tool.ERASER && g is Gesture.Stroke && !g.blocked && pressed == 1) pos else null
                            if (g is Gesture.Stroke && !g.blocked) primary?.consume()
                            if (g === Gesture.Transform && pressed > 0) {
                                // pinch-zoom and pan around the finger centroid
                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()
                                val centroid = event.calculateCentroid(useCurrent = true)
                                val pw = vm.project.width
                                val ph = vm.project.height
                                val oldS = vt.scale(cw, ch, pw, ph)
                                val oldO = vt.origin(cw, ch, pw, ph, oldS)
                                val q = ((centroid - panChange) - oldO) / oldS
                                vt.zoom = (vt.zoom * zoomChange).coerceIn(0.5f, 16f)
                                val newS = vt.scale(cw, ch, pw, ph)
                                val newO = centroid - q * newS
                                vt.pan = newO - vt.base(cw, ch, pw, ph, newS)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (event == null || event.changes.any { it.pressed })

                        val (_, upAction) = GestureReducer.reduce(gesture, GestureEvent.Up, slopPx)
                        perform(upAction, down.position.x, down.position.y)
                    } finally {
                        // Cancellation (dispose, back, parent intercept) must never leave a live stroke behind.
                        session?.let { vm.cancelStroke(it) }
                        eraseTouch = null
                    }
                }
            },
    ) {
        val pw = project.width
        val ph = project.height
        val s = vt.scale(size.width, size.height, pw, ph)
        val o = vt.origin(size.width, size.height, pw, ph, s)
        @Suppress("UNUSED_VARIABLE")
        val tick = vm.liveTick // subscribe to live stroke changes
        val look = vm.lookSettings
        val allowances = vm.anim.allowances(project.layers, look)
        val drawingIdx = if (vm.anim.active) vm.anim.drawingIndex(look) else 0
        val live = vm.liveStroke()
        val liveLayerId = vm.liveLayerId
        // Pen sprite + follow camera: playback only (never while the user draws, pinches or pans). Same MotionFx as export.
        val motion = if (vm.anim.active && live == null && vt.zoom == 1f && vt.pan == Offset.Zero) {
            MotionFx.prepare(project.layers, look, vm.anim.progress, vm.anim.durationSec, pw, ph)
        } else null

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
            PaperRender.drawPaper(c, pw, ph, look) // Look: tooth / fibre paper over the plain sheet
            Renderer.drawBackground(c, project)
            c.restore()
            // Onion skin (screen only): neighbours of the open frame, tinted, under its own drawing.
            val cur = project.currentFrameIndex
            if (vm.onionEnabled && !vm.anim.active && !vm.flipbookPlaying && project.frames.size > 1) {
                val strength = vm.onionOpacity.coerceIn(0f, 1f)
                onionCache.draw(
                    c, s, pw, ph,
                    prev = project.frames.getOrNull(cur - 1),
                    next = project.frames.getOrNull(cur + 1),
                    prevTint = onionPrevTint,
                    nextTint = onionNextTint,
                    prevAlpha = (strength * 255f).toInt(),
                    nextAlpha = (strength * 0.8f * 255f).toInt(),
                )
            }
            // Post filter (1-bit / halftone) is costly: preview it only when nothing is drawn or played.
            val still = live == null && !vm.anim.active && !vm.flipbookPlaying
            val filtered = look.post != PostFilter.NONE && still &&
                postPreview.draw(c, s, pw, ph, project.layers, look)
            if (filtered) {
                // nothing: the post-filtered still was drawn above
            } else if (motion != null && look.cameraFollow) {
                // Camera: the layer cache stays at ONE scale (s * full zoom) for the whole playback and is scaled
                // down by zoom/fullZoom, so it never rebuilds per frame and stays sharp when zoomed in.
                val zp = look.cameraZoom.coerceIn(CameraFollow.ZOOM_MIN, CameraFollow.ZOOM_MAX)
                val cam = motion.camera
                c.save()
                c.clipRect(0f, 0f, pw * s, ph * s)
                c.translate(cam.tx * s, cam.ty * s)
                c.scale(cam.zoom / zp, cam.zoom / zp)
                layerCache.draw(c, s * zp, pw, ph, project.layers, allowances, live, liveLayerId, look, drawingIdx)
                c.restore()
            } else {
                layerCache.draw(c, s, pw, ph, project.layers, allowances, live, liveLayerId, look, drawingIdx)
            }
            if (motion != null && look.showPen) {
                c.save()
                c.clipRect(0f, 0f, pw * s, ph * s)
                c.scale(s, s)
                motion.camera.applyTo(c)
                motion.drawPen(c, project.layers)
                c.restore()
            }
            c.restore()
        }
        // Size circle: under the finger while erasing, or mid-sheet while a size slider moves. Radius follows zoom.
        val ring = eraseTouch ?: if (sizePreview) Offset(size.width / 2f, vt.insetTop + (size.height - vt.insetTop - vt.insetBottom) / 2f) else null
        if (ring != null) {
            val r = BrushSizeMath.screenRadius(if (vm.tool == Tool.ERASER) vm.eraserSize else vm.brushSize, s).coerceAtLeast(1f)
            drawCircle(Color.White, r, ring, style = DrawStroke(3.dp.toPx()))
            drawCircle(Color(0xFF222222), r, ring, style = DrawStroke(1.5.dp.toPx()))
        }
    }
}
