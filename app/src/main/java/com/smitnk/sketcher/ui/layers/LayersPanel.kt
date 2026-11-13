package com.smitnk.sketcher.ui.layers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Texture
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.smitnk.sketcher.image.Stroke
import com.smitnk.sketcher.model.Layer
import com.smitnk.sketcher.model.Project
import com.smitnk.sketcher.ui.theme.SketcherTokens
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private enum class LayerKind(val label: String, val icon: ImageVector) {
    Outline("Ink", Icons.Default.Edit),
    Shading("Graphite", Icons.Default.Texture),
    Colour("Ochre", Icons.Default.WaterDrop),
    Manual("Manual", Icons.Default.Brush),
}

private fun kindOf(name: String): LayerKind {
    val n = name.lowercase()
    return when {
        "outline" in n || "ink" in n || n.endsWith("line") || n.endsWith("lines") -> LayerKind.Outline
        "shad" in n || "hatch" in n -> LayerKind.Shading
        "colour" in n || "color" in n || "fill" in n -> LayerKind.Colour
        else -> LayerKind.Manual
    }
}

@Composable
private fun LayerKind.tint(): Color = when (this) {
    LayerKind.Outline -> MaterialTheme.colorScheme.onSurface
    LayerKind.Shading -> MaterialTheme.colorScheme.secondary
    LayerKind.Colour -> MaterialTheme.colorScheme.tertiary
    LayerKind.Manual -> MaterialTheme.colorScheme.primary
}

/**
 * Right-side drawer over the canvas (design-system 8.4). On phones the drawer is up to 92% of the
 * width (max 340dp) so that eye, lock and overflow keep 48dp touch targets next to the name.
 */
@Composable
fun LayersPanel(
    project: Project,
    onAddLayer: () -> Unit,
    onSelectLayer: (Layer) -> Unit,
    onMoveLayer: (Layer, Int) -> Unit,
    onDeleteLayer: (Layer) -> Unit,
    onDismiss: () -> Unit,
) {
    var renaming by remember { mutableStateOf<Layer?>(null) }
    var deleting by remember { mutableStateOf<Layer?>(null) }
    val expanded = remember { mutableStateListOf<Long>() }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val interaction = remember { MutableInteractionSource() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(SketcherTokens.colors.scrim)
                .clickable(interactionSource = interaction, indication = null, onClick = onDismiss),
        ) {
            val drawerWidth = min(340f, maxWidth.value * 0.92f).dp
            AnimatedVisibility(
                visible = shown,
                modifier = Modifier.align(Alignment.CenterEnd),
                enter = slideInHorizontally(tween(300, easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f))) { it } +
                    fadeIn(tween(120)),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp),
                    tonalElevation = SketcherTokens.elevationSheet,
                    modifier = Modifier
                        .width(drawerWidth)
                        .fillMaxHeight()
                        // Swallow taps so they do not reach the scrim.
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
                ) {
                    Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Layers",
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.weight(1f).semantics { heading() },
                            )
                            IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Close layers")
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        // Top of the stack first.
                        val ordered = project.layers.reversed()
                        LazyColumn(Modifier.weight(1f)) {
                            items(ordered, key = { it.id }) { layer ->
                                val index = project.layers.indexOf(layer)
                                LayerRow(
                                    layer = layer,
                                    project = project,
                                    selected = project.selectedLayer === layer,
                                    expanded = layer.id in expanded,
                                    canMoveUp = index in 0 until project.layers.lastIndex,
                                    canMoveDown = index > 0,
                                    onSelect = {
                                        if (project.selectedLayer === layer) {
                                            if (layer.id in expanded) expanded.remove(layer.id) else expanded.add(layer.id)
                                        } else {
                                            onSelectLayer(layer)
                                        }
                                    },
                                    onRename = { renaming = layer },
                                    onMove = { delta -> onMoveLayer(layer, delta) },
                                    onDelete = { deleting = layer },
                                )
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        if (project.background != null) PhotoRow(project)
                        Button(
                            onClick = onAddLayer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                                .heightIn(min = 52.dp),
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Add layer")
                        }
                    }
                }
            }
        }
    }

    renaming?.let { layer ->
        var text by remember(layer) { mutableStateOf(layer.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename layer") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    if (text.isNotBlank()) layer.name = text.trim()
                    renaming = null
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }

    deleting?.let { layer ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete layer?") },
            text = { Text("\"${layer.name}\" and its strokes will be removed.") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteLayer(layer)
                    deleting = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun LayerRow(
    layer: Layer,
    project: Project,
    selected: Boolean,
    expanded: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    val kind = kindOf(layer.name)
    val tint = kind.tint()
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    var menuOpen by remember { mutableStateOf(false) }
    val summary = "Layer: ${layer.name}, ${kind.label}, " +
        (if (layer.visible) "visible" else "hidden") + (if (selected) ", selected" else "")

    Column(Modifier.fillMaxWidth().background(bg)) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            // Accent bar for the selected layer.
            Box(
                Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
            )
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = 72.dp)
                    .clickable(onClickLabel = if (selected) "Show opacity" else "Select layer", onClick = onSelect)
                    .semantics(mergeDescendants = true) { contentDescription = summary }
                    .padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LayerThumbnail(layer, project.width, project.height, tint)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        layer.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    KindBadge(kind, tint)
                }
            }
            IconButton(onClick = { layer.visible = !layer.visible }, modifier = Modifier.size(48.dp).align(Alignment.CenterVertically)) {
                Icon(
                    if (layer.visible) Icons.Default.Visibility else Icons.Outlined.VisibilityOff,
                    contentDescription = if (layer.visible) "Hide ${layer.name}" else "Show ${layer.name}",
                )
            }
            IconButton(onClick = { layer.locked = !layer.locked }, modifier = Modifier.size(48.dp).align(Alignment.CenterVertically)) {
                Icon(
                    if (layer.locked) Icons.Default.Lock else Icons.Outlined.LockOpen,
                    contentDescription = if (layer.locked) "Unlock ${layer.name}" else "Lock ${layer.name}",
                )
            }
            Box(Modifier.align(Alignment.CenterVertically)) {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More actions for ${layer.name}")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = { menuOpen = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text("Move up") },
                        enabled = canMoveUp,
                        onClick = { menuOpen = false; onMove(+1) },
                    )
                    DropdownMenuItem(
                        text = { Text("Move down") },
                        enabled = canMoveDown,
                        onClick = { menuOpen = false; onMove(-1) },
                    )
                    DropdownMenuItem(
                        text = { Text("Include in animation") },
                        trailingIcon = {
                            if (layer.inAnimation) Icon(Icons.Default.Check, contentDescription = "Included")
                        },
                        onClick = { layer.inAnimation = !layer.inAnimation },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        }
        if (expanded) {
            OpacityRow(
                label = "Opacity",
                value = layer.opacity,
                onChange = { layer.opacity = it },
                modifier = Modifier.padding(start = 20.dp, end = 16.dp, bottom = 8.dp),
            )
        }
    }
}

@Composable
private fun KindBadge(kind: LayerKind, tint: Color) {
    Row(
        Modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .border(1.dp, tint.copy(alpha = 0.6f), MaterialTheme.shapes.extraSmall)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(kind.icon, contentDescription = null, tint = tint, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(4.dp))
        Text(kind.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun OpacityRow(label: String, value: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    val pct = (value * 100).roundToInt()
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = value,
            onValueChange = onChange,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .semantics { stateDescription = "$label $pct percent" },
        )
        Text("$pct%", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(40.dp))
    }
}

@Composable
private fun PhotoRow(project: Project) {
    val bitmap = project.background ?: return
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                bitmap = image,
                contentDescription = "Reference photo thumbnail",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(40.dp)
                    .clip(MaterialTheme.shapes.small)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Reference photo", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Not exported", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(
                onClick = { project.backgroundVisible = !project.backgroundVisible },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    if (project.backgroundVisible) Icons.Default.Visibility else Icons.Outlined.VisibilityOff,
                    contentDescription = if (project.backgroundVisible) "Hide reference photo" else "Show reference photo",
                )
            }
        }
        OpacityRow(
            label = "Photo",
            value = project.backgroundOpacity,
            onChange = { project.backgroundOpacity = it },
            modifier = Modifier.padding(end = 12.dp),
        )
    }
}

// ---- Thumbnails -------------------------------------------------------------------------------

private class ThumbStroke(val path: Path, val color: Color, val width: Float, val dot: Boolean)

private const val MAX_THUMB_STROKES = 400
private const val MAX_POINTS_PER_STROKE = 80

private fun buildThumb(strokes: List<Stroke>): List<ThumbStroke> {
    if (strokes.isEmpty()) return emptyList()
    val stride = max(1, strokes.size / MAX_THUMB_STROKES)
    val out = ArrayList<ThumbStroke>(min(strokes.size, MAX_THUMB_STROKES + 1))
    var s = 0
    while (s < strokes.size) {
        val st = strokes[s]
        val p = st.points
        val n = p.size / 2
        if (n >= 1) {
            val step = max(1, n / MAX_POINTS_PER_STROKE)
            val path = Path()
            path.moveTo(p[0], p[1])
            var i = step
            while (i < n) {
                path.lineTo(p[i * 2], p[i * 2 + 1])
                i += step
            }
            path.lineTo(p[(n - 1) * 2], p[(n - 1) * 2 + 1])
            out.add(ThumbStroke(path, Color(st.color).copy(alpha = st.alpha.coerceIn(0.15f, 1f)), st.width, n == 1))
        }
        s += stride
    }
    return out
}

@Composable
private fun LayerThumbnail(layer: Layer, pw: Int, ph: Int, tint: Color) {
    val count = layer.strokes.size
    val first = layer.strokes.firstOrNull()
    val last = layer.strokes.lastOrNull()
    val thumb = remember(layer, count, first, last) { buildThumb(layer.strokes.toList()) }
    val a = SketcherTokens.colors.checkerA
    val b = SketcherTokens.colors.checkerB
    val alpha = if (layer.visible) 1f else 0.45f
    val shape = MaterialTheme.shapes.small

    Canvas(
        Modifier
            .size(56.dp)
            .clip(shape)
            .border(1.dp, tint.copy(alpha = 0.5f), shape),
    ) {
        val tile = 7.dp.toPx()
        val cols = (size.width / tile).toInt() + 1
        val rows = (size.height / tile).toInt() + 1
        for (r in 0 until rows) for (c in 0 until cols) {
            drawRect(
                color = if ((r + c) % 2 == 0) a else b,
                topLeft = Offset(c * tile, r * tile),
                size = Size(tile, tile),
            )
        }
        val w = max(pw, 1).toFloat()
        val h = max(ph, 1).toFloat()
        val scale = min(size.width / w, size.height / h)
        withTransform({
            translate((size.width - w * scale) / 2f, (size.height - h * scale) / 2f)
            scale(scale, scale, pivot = Offset.Zero)
            clipRect(0f, 0f, w, h)
        }) {
            val hairline = 1f / scale
            for (t in thumb) {
                val sw = max(t.width, hairline)
                drawPath(
                    path = t.path,
                    color = t.color,
                    alpha = alpha,
                    style = DrawStroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }
    }
}
