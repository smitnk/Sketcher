package com.smitnk.sketcher.ui.editor

import android.graphics.Color as AndroidColor
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Texture
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.smitnk.sketcher.image.BrushType
import com.smitnk.sketcher.model.Layer
import com.smitnk.sketcher.ui.Tool
import com.smitnk.sketcher.ui.theme.SketcherTokens
import kotlin.math.roundToInt

/** Curated ink palette in the "Artist's Sketchbook" family (ink, graphite, paper, vermilion, ochre + muted pigments). */
private val INK_PALETTE: List<Int> = listOf(
    0xFF1B1A17, 0xFF5B574E, 0xFF8A8478, 0xFFFFFDF8, 0xFFC23B22, 0xFF8A6A12,
    0xFF6D4C41, 0xFF2F3E6B, 0xFF3E6A8F, 0xFF2F6B63, 0xFF4F7A3A, 0xFF7A3B5E,
).map { it.toInt() }

private const val MAX_RECENT = 6

private enum class DockPanel { NONE, OPTIONS, COLOR }

private class DockTool(val label: String, val icon: ImageVector?, val brush: BrushType?)

private val DOCK_TOOLS: List<DockTool> = listOf(
    DockTool("Pen", Icons.Default.Draw, BrushType.PEN),
    DockTool("Pencil", Icons.Default.Edit, BrushType.PENCIL),
    DockTool("Marker", Icons.Default.Brush, BrushType.MARKER),
    DockTool("Soft", Icons.Default.BlurOn, BrushType.SOFT),
    DockTool("Hatch", Icons.Default.Texture, BrushType.HATCH),
    DockTool("Eraser", null, null),
)

private val EnterEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val ExitEasing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

/**
 * Compact floating tool dock. Emits one floating surface; call inside a Column.
 * Tap the active tool to show/hide size + opacity; tap the colour swatch for the colour panel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolDock(
    tool: Tool,
    onToolChange: (Tool) -> Unit,
    brushType: BrushType,
    onSelectBrush: (BrushType) -> Unit,
    brushColor: Int,
    onColorSelected: (Int) -> Unit,
    brushSize: Float,
    onBrushSizeChange: (Float) -> Unit,
    brushAlpha: Float,
    onBrushAlphaChange: (Float) -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onLayers: () -> Unit,
    onAddShadingLayer: () -> Unit,
    selectedLayer: Layer?,
    modifier: Modifier = Modifier,
) {
    var panel by rememberSaveable { mutableStateOf(DockPanel.NONE) }
    val recent: SnapshotStateList<Int> = remember { mutableStateListOf() }
    val erasing = tool == Tool.ERASER

    fun pushRecent(argb: Int) {
        recent.remove(argb)
        recent.add(0, argb)
        while (recent.size > MAX_RECENT) recent.removeAt(recent.size - 1)
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = SketcherTokens.colors.floatingSurface,
        contentColor = SketcherTokens.colors.onFloatingSurface,
        shadowElevation = SketcherTokens.elevationFloating,
    ) {
        Column(Modifier.padding(horizontal = SketcherTokens.space8, vertical = SketcherTokens.space4)) {
            // Row A: tool toggles (icon + label).
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SketcherTokens.space4),
            ) {
                DOCK_TOOLS.forEach { t ->
                    val selected = if (t.brush == null) erasing else (!erasing && brushType == t.brush)
                    ToolToggle(t, selected) {
                        if (selected) {
                            panel = if (panel == DockPanel.OPTIONS) DockPanel.NONE else DockPanel.OPTIONS
                        } else {
                            if (t.brush == null) {
                                onToolChange(Tool.ERASER)
                            } else {
                                onToolChange(Tool.BRUSH)
                                onSelectBrush(t.brush)
                            }
                        }
                    }
                }
            }

            // Row B: undo / redo, colour swatch, shading chip, layers.
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SketcherTokens.space4),
            ) {
                IconButton(onClick = onUndo, enabled = canUndo) {
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                }
                IconButton(onClick = onRedo, enabled = canRedo) {
                    Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
                }
                ColorSwatchButton(
                    argb = brushColor,
                    open = panel == DockPanel.COLOR,
                    onClick = { panel = if (panel == DockPanel.COLOR) DockPanel.NONE else DockPanel.COLOR },
                )
                AssistChip(
                    onClick = onAddShadingLayer,
                    label = { Text("+ Shading layer", style = MaterialTheme.typography.labelLarge) },
                    leadingIcon = {
                        Icon(Icons.Default.Texture, contentDescription = null, modifier = Modifier.size(18.dp))
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        leadingIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                    border = null,
                    modifier = Modifier.semantics { contentDescription = "Add shading layer" },
                )
            }

            AnimatedVisibility(
                visible = panel == DockPanel.OPTIONS,
                enter = expandVertically(tween(240, easing = EnterEasing)) + fadeIn(tween(240)),
                exit = shrinkVertically(tween(170, easing = ExitEasing)) + fadeOut(tween(120)),
            ) {
                OptionsPanel(
                    erasing = erasing,
                    brushColor = brushColor,
                    brushSize = brushSize,
                    onBrushSizeChange = onBrushSizeChange,
                    brushAlpha = brushAlpha,
                    onBrushAlphaChange = onBrushAlphaChange,
                )
            }

            AnimatedVisibility(
                visible = panel == DockPanel.COLOR,
                enter = expandVertically(tween(240, easing = EnterEasing)) + fadeIn(tween(240)),
                exit = shrinkVertically(tween(170, easing = ExitEasing)) + fadeOut(tween(120)),
            ) {
                ColorPanel(
                    brushColor = brushColor,
                    recent = recent,
                    onColorSelected = onColorSelected,
                    onCommit = { pushRecent(it) },
                )
            }

            val layer = selectedLayer
            if (layer != null && (layer.locked || !layer.visible)) {
                Text(
                    "\"${layer.name}\" is ${if (layer.locked) "locked" else "hidden"} - drawing disabled",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = SketcherTokens.space8, vertical = SketcherTokens.space4),
                )
            }
        }
    }
}

@Composable
private fun ToolToggle(t: DockTool, selected: Boolean, onClick: () -> Unit) {
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier
            .defaultMinSize(minWidth = SketcherTokens.minTouchTarget, minHeight = SketcherTokens.minTouchTarget)
            .clip(MaterialTheme.shapes.medium)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = t.label }
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (t.icon != null) {
            Icon(t.icon, contentDescription = null, tint = fg, modifier = Modifier.size(22.dp))
        } else {
            EraserGlyph(fg)
        }
        Text(
            t.label,
            color = fg,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

/** Simple eraser block drawn with Canvas (no icon dependency). */
@Composable
private fun EraserGlyph(tint: Color) {
    Canvas(Modifier.size(22.dp)) {
        rotate(-45f) {
            val w = size.width * 0.95f
            val h = size.height * 0.5f
            val tl = Offset((size.width - w) / 2f, (size.height - h) / 2f)
            drawRoundRect(tint, tl, Size(w, h), CornerRadius(3.dp.toPx()), style = Stroke(2.dp.toPx()))
            drawRect(tint, tl, Size(w * 0.42f, h))
        }
    }
}

@Composable
private fun ColorSwatchButton(argb: Int, open: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(SketcherTokens.minTouchTarget)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = "Colour"
                stateDescription = if (open) "Colour panel open" else "Colour panel closed"
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(Color(argb))
                .border(
                    width = if (open) 3.dp else 1.5.dp,
                    color = if (open) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    shape = CircleShape,
                ),
        )
    }
}

@Composable
private fun OptionsPanel(
    erasing: Boolean,
    brushColor: Int,
    brushSize: Float,
    onBrushSizeChange: (Float) -> Unit,
    brushAlpha: Float,
    onBrushAlphaChange: (Float) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = SketcherTokens.space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrushPreviewDot(erasing, brushColor, brushSize, brushAlpha)
        Column(Modifier.weight(1f).padding(start = SketcherTokens.space8)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Size ${brushSize.roundToInt()}",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.width(60.dp),
                )
                Slider(
                    value = brushSize,
                    onValueChange = onBrushSizeChange,
                    valueRange = 1f..40f,
                    modifier = Modifier.weight(1f).semantics { stateDescription = "Size ${brushSize.roundToInt()}" },
                )
            }
            if (!erasing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Opacity ${(brushAlpha * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.width(60.dp),
                    )
                    Slider(
                        value = brushAlpha,
                        onValueChange = onBrushAlphaChange,
                        valueRange = 0.05f..1f,
                        modifier = Modifier.weight(1f)
                            .semantics { stateDescription = "Opacity ${(brushAlpha * 100).roundToInt()} percent" },
                    )
                }
            }
        }
    }
}

/** Live preview of the current brush: size, colour and opacity over a paper swatch. */
@Composable
private fun BrushPreviewDot(erasing: Boolean, argb: Int, size: Float, alpha: Float) {
    val paper = SketcherTokens.colors.paper
    val outline = MaterialTheme.colorScheme.outlineVariant
    val ring = MaterialTheme.colorScheme.outline
    Canvas(
        Modifier
            .size(56.dp)
            .clip(MaterialTheme.shapes.small)
            .background(paper)
            .border(1.dp, outline, MaterialTheme.shapes.small)
            .semantics { contentDescription = "Brush preview" },
    ) {
        val d = (6.dp.toPx() + (size.coerceIn(1f, 40f) / 40f) * 42.dp.toPx())
        val c = Offset(this.size.width / 2f, this.size.height / 2f)
        if (erasing) {
            drawCircle(ring, radius = d / 2f, center = c, style = Stroke(1.5.dp.toPx()))
        } else {
            drawCircle(Color(argb).copy(alpha = alpha), radius = d / 2f, center = c)
        }
    }
}

@Composable
private fun ColorPanel(
    brushColor: Int,
    recent: List<Int>,
    onColorSelected: (Int) -> Unit,
    onCommit: (Int) -> Unit,
) {
    var hue by remember { mutableFloatStateOf(0f) }
    var sat by remember { mutableFloatStateOf(0f) }
    var value by remember { mutableFloatStateOf(0f) }
    val select by rememberUpdatedState(onColorSelected)
    val commit by rememberUpdatedState(onCommit)

    fun current(): Int = AndroidColor.HSVToColor(floatArrayOf(hue, sat, value))
    fun emit() = select(current())

    // Sync from the outside (palette tap, recent, or other source). Hue is kept for greys.
    LaunchedEffect(brushColor) {
        if ((current() or 0xFF000000.toInt()) != (brushColor or 0xFF000000.toInt())) {
            val hsv = FloatArray(3)
            AndroidColor.colorToHSV(brushColor, hsv)
            if (hsv[1] > 0.02f && hsv[2] > 0.02f) hue = hsv[0]
            sat = hsv[1]
            value = hsv[2]
        }
    }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = SketcherTokens.space8, vertical = SketcherTokens.space4),
        horizontalArrangement = Arrangement.spacedBy(SketcherTokens.space12),
    ) {
        // Saturation / value square.
        val thumbRing = MaterialTheme.colorScheme.surface
        Canvas(
            Modifier
                .size(120.dp)
                .clip(MaterialTheme.shapes.small)
                .semantics { contentDescription = "Saturation and brightness" }
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { p ->
                        sat = (p.x / size.width).coerceIn(0f, 1f)
                        value = 1f - (p.y / size.height).coerceIn(0f, 1f)
                        emit()
                        commit(current())
                    })
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = { commit(current()) },
                    ) { change, _ ->
                        change.consume()
                        sat = (change.position.x / size.width).coerceIn(0f, 1f)
                        value = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                        emit()
                    }
                },
        ) {
            drawRect(Brush.horizontalGradient(listOf(Color.White, Color.hsv(hue, 1f, 1f))))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            val c = Offset(sat * size.width, (1f - value) * size.height)
            drawCircle(Color.Black, radius = 9.dp.toPx(), center = c, style = Stroke(1.5.dp.toPx()))
            drawCircle(Color.White, radius = 7.5.dp.toPx(), center = c, style = Stroke(2.dp.toPx()))
        }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SketcherTokens.space8)) {
            // Hue bar.
            val hueColors = remember { (0..6).map { Color.hsv(it * 60f, 1f, 1f) } }
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .semantics { contentDescription = "Hue" }
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { p ->
                            hue = (p.x / size.width).coerceIn(0f, 1f) * 360f
                            if (sat < 0.05f) sat = 0.6f
                            if (value < 0.05f) value = 0.8f
                            emit()
                            commit(current())
                        })
                    }
                    .pointerInput(Unit) {
                        detectDragGestures(onDragEnd = { commit(current()) }) { change, _ ->
                            change.consume()
                            hue = (change.position.x / size.width).coerceIn(0f, 1f) * 360f
                            if (sat < 0.05f) sat = 0.6f
                            if (value < 0.05f) value = 0.8f
                            emit()
                        }
                    },
            ) {
                drawRect(Brush.horizontalGradient(hueColors))
                val x = (hue / 360f) * size.width
                drawCircle(Color.Black, radius = 11.dp.toPx(), center = Offset(x, size.height / 2f), style = Stroke(1.5.dp.toPx()))
                drawCircle(Color.White, radius = 9.5.dp.toPx(), center = Offset(x, size.height / 2f), style = Stroke(2.dp.toPx()))
            }

            // Curated palette: two rows of six.
            INK_PALETTE.chunked(6).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    row.forEach { argb ->
                        ColorDot(argb, brushColor == argb, "Ink colour") {
                            select(argb)
                            commit(argb)
                        }
                    }
                }
            }

            // Recent colours.
            if (recent.isEmpty()) {
                Text(
                    "Recent colours appear here",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.height(28.dp),
                )
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SketcherTokens.space4)) {
                    recent.forEach { argb ->
                        ColorDot(argb, brushColor == argb, "Recent colour") {
                            select(argb)
                            commit(argb)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColorDot(argb: Int, selected: Boolean, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(Color(argb))
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape = CircleShape,
            )
            .semantics {
                contentDescription = description
                stateDescription = if (selected) "Selected" else "Not selected"
            }
            .clickable(role = Role.Button, onClick = onClick),
    )
}
