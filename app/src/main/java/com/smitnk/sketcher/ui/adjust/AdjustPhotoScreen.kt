@file:OptIn(ExperimentalMaterial3Api::class)

package com.smitnk.sketcher.ui.adjust

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.smitnk.sketcher.image.pipeline.AspectPreset
import com.smitnk.sketcher.image.pipeline.InputFrame
import com.smitnk.sketcher.image.pipeline.SizePreset
import com.smitnk.sketcher.ui.theme.SketcherTokens
import kotlin.math.min
import kotlin.math.roundToInt

/** Full-screen "Adjust photo" step: pinch/pan the photo, drag the crop corners, pick shape and size. */
@Composable
fun AdjustPhotoScreen(
    photo: Bitmap,
    onUse: (InputFrame, Boolean) -> Unit,
    onAuto: () -> Unit,
    onSkip: () -> Unit,
    onDontAskChange: (Boolean) -> Unit,
    initial: InputFrame,
    onCancel: () -> Unit,
    pageSize: Pair<Int, Int>? = null,
) {
    val pw = photo.width
    val ph = photo.height
    val photoAspect = pw.toFloat() / ph
    var aspect by remember { mutableStateOf(if (initial.auto) AspectPreset.ORIGINAL else initial.aspect) }
    var rect by remember {
        mutableStateOf(
            if (initial.auto) CropRect(0f, 0f, 1f, 1f)
            else CropRect(initial.left, initial.top, initial.right, initial.bottom),
        )
    }
    var size by remember { mutableStateOf(if (initial.auto) SizePreset.MEDIUM else initial.size) }
    var custom by remember { mutableFloatStateOf(initial.customLongSide.toFloat()) }
    var dontAsk by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val lock: Float? = when (aspect) {
        AspectPreset.FREE -> null
        AspectPreset.ORIGINAL -> photoAspect
        else -> aspect.ratio
    }
    val frame = InputFrame(
        auto = false, aspect = aspect, left = rect.l, top = rect.t, right = rect.r, bottom = rect.b,
        size = size, customLongSide = custom.roundToInt(),
    )
    val differs = pageSize != null && frame.canvasSize(pw, ph) != pageSize

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = SketcherTokens.screenMargin, vertical = SketcherTokens.space8)) {
                Text("Adjust photo", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Pinch to zoom. Drag the box or its corners.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                BoxWithConstraints(
                    Modifier.weight(1.2f).heightIn(min = 160.dp).fillMaxWidth().padding(vertical = SketcherTokens.space8).clipToBounds()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, p, z, _ ->
                                zoom = (zoom * z).coerceIn(1f, 8f)
                                pan += p
                            }
                        },
                ) {
                    val density = LocalDensity.current
                    val boxW = with(density) { maxWidth.toPx() }
                    val boxH = with(density) { maxHeight.toPx() }
                    val dw = pw * min(boxW / pw, boxH / ph) * zoom
                    val dh = dw / photoAspect
                    val ox = (boxW - dw) / 2f + pan.x
                    val oy = (boxH - dh) / 2f + pan.y
                    Image(
                        photo.asImageBitmap(), contentDescription = "Your photo",
                        modifier = Modifier
                            .offset { IntOffset(ox.roundToInt(), oy.roundToInt()) }
                            .size(with(density) { dw.toDp() }, with(density) { dh.toDp() }),
                    )
                    Canvas(Modifier.fillMaxSize()) {
                        val l = ox + rect.l * dw
                        val t = oy + rect.t * dh
                        val r = ox + rect.r * dw
                        val b = oy + rect.b * dh
                        val dim = Color.Black.copy(alpha = 0.5f)
                        drawRect(dim, Offset.Zero, androidx.compose.ui.geometry.Size(this.size.width, t.coerceAtLeast(0f)))
                        drawRect(dim, Offset(0f, b), androidx.compose.ui.geometry.Size(this.size.width, (this.size.height - b).coerceAtLeast(0f)))
                        drawRect(dim, Offset(0f, t), androidx.compose.ui.geometry.Size(l.coerceAtLeast(0f), b - t))
                        drawRect(dim, Offset(r, t), androidx.compose.ui.geometry.Size((this.size.width - r).coerceAtLeast(0f), b - t))
                        drawRect(Color.White, Offset(l, t), androidx.compose.ui.geometry.Size(r - l, b - t), style = Stroke(2.dp.toPx()))
                    }
                    Box(
                        Modifier
                            .offset { IntOffset((ox + rect.l * dw).roundToInt(), (oy + rect.t * dh).roundToInt()) }
                            .size(with(density) { ((rect.r - rect.l) * dw).toDp() }, with(density) { ((rect.b - rect.t) * dh).toDp() })
                            .pointerInput(dw, dh) {
                                detectDragGestures { change, d ->
                                    change.consume()
                                    rect = moveRect(rect, d.x / dw, d.y / dh)
                                }
                            },
                    )
                    for (corner in Corner.values()) {
                        val cx = ox + (if (corner == Corner.TL || corner == Corner.BL) rect.l else rect.r) * dw
                        val cy = oy + (if (corner == Corner.TL || corner == Corner.TR) rect.t else rect.b) * dh
                        val half = with(density) { (SketcherTokens.minTouchTarget / 2).toPx() }
                        Box(
                            Modifier
                                .offset { IntOffset((cx - half).roundToInt(), (cy - half).roundToInt()) }
                                .size(SketcherTokens.minTouchTarget)
                                .semantics { contentDescription = "Crop corner" }
                                .pointerInput(corner, dw, dh, lock) {
                                    detectDragGestures { change, d ->
                                        change.consume()
                                        val x = (if (corner == Corner.TL || corner == Corner.BL) rect.l else rect.r) + d.x / dw
                                        val y = (if (corner == Corner.TL || corner == Corner.TR) rect.t else rect.b) + d.y / dh
                                        rect = dragCorner(rect, corner, x, y, lock, photoAspect)
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(Modifier.size(20.dp).background(Color.White, androidx.compose.foundation.shape.CircleShape))
                        }
                    }
                }

                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                if (pageSize != null) {
                    Text("Page size now: ${pageSize.first} × ${pageSize.second}", style = MaterialTheme.typography.bodyMedium)
                }
                Text("Shape", style = MaterialTheme.typography.titleSmall)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(SketcherTokens.space8),
                ) {
                    for (a in AspectPreset.values()) {
                        FilterChip(
                            selected = aspect == a,
                            onClick = {
                                aspect = a
                                if (a != AspectPreset.FREE) {
                                    val c = InputFrame.centredCrop(pw, ph, if (a == AspectPreset.ORIGINAL) null else a.ratio)
                                    rect = CropRect(c[0], c[1], c[2], c[3])
                                }
                            },
                            label = { Text(a.label) },
                            modifier = Modifier.heightIn(min = SketcherTokens.minTouchTarget),
                        )
                    }
                }
                Hint("Free lets you crop any shape.")

                Text("Size", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = SketcherTokens.space8))
                val sizes = SizePreset.values()
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    sizes.forEachIndexed { i, s ->
                        SegmentedButton(
                            selected = size == s,
                            onClick = { size = s },
                            shape = SegmentedButtonDefaults.itemShape(i, sizes.size),
                            modifier = Modifier.heightIn(min = SketcherTokens.minTouchTarget),
                        ) { Text(s.label, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                    }
                }
                if (size == SizePreset.CUSTOM) {
                    Slider(
                        value = custom,
                        onValueChange = { custom = ((it / 8f).roundToInt() * 8).toFloat() },
                        valueRange = InputFrame.MIN_SIDE.toFloat()..InputFrame.MAX_SIDE.toFloat(),
                        steps = (InputFrame.MAX_SIDE - InputFrame.MIN_SIDE) / 8 - 1,
                        modifier = Modifier.semantics { contentDescription = "Longest side in pixels" },
                    )
                }
                Text(
                    "Final size: ${frame.sizeLabel(pw, ph)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (frame.isSlow) {
                    Text("Large size is slower", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                } else {
                    Hint("Bigger pictures keep more detail but take longer.")
                }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = SketcherTokens.minTouchTarget)
                        .toggleable(dontAsk, role = Role.Checkbox) { dontAsk = it; onDontAskChange(it) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = dontAsk, onCheckedChange = null)
                    Column(Modifier.padding(start = SketcherTokens.space8)) {
                        Text("Don't ask again", style = MaterialTheme.typography.bodyMedium)
                        Hint("Skip this step for new photos.")
                    }
                }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(SketcherTokens.space8, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onAuto, Modifier.heightIn(min = SketcherTokens.minTouchTarget)) { Text("Auto") }
                    TextButton(onClick = onSkip, Modifier.heightIn(min = SketcherTokens.minTouchTarget)) { Text("Skip") }
                    if (!differs) Button(onClick = { onUse(frame, false) }, Modifier.heightIn(min = SketcherTokens.minTouchTarget)) { Text("Use photo") }
                }
                if (differs) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SketcherTokens.space8)) {
                        Column(Modifier.weight(1f)) {
                            Button(onClick = { onUse(frame, false) }, Modifier.fillMaxWidth().heightIn(min = SketcherTokens.minTouchTarget)) { Text("Fit in page") }
                            Hint("Keep this photo whole, with paper around it.")
                        }
                        Column(Modifier.weight(1f)) {
                            Button(onClick = { onUse(frame, true) }, Modifier.fillMaxWidth().heightIn(min = SketcherTokens.minTouchTarget)) { Text("Make this the page size") }
                            Hint("All frames change to this size.")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) = Text(
    text,
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
)
