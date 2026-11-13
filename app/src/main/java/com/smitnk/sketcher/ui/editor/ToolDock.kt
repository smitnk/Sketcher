package com.smitnk.sketcher.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.smitnk.sketcher.image.BrushType
import com.smitnk.sketcher.model.Layer
import com.smitnk.sketcher.ui.Tool
import kotlin.math.roundToInt

private val SWATCHES: List<Int> = listOf(
    0xFF000000, 0xFF555555, 0xFFFFFFFF, 0xFFE53935, 0xFFFB8C00, 0xFFFDD835,
    0xFF43A047, 0xFF00897B, 0xFF1E88E5, 0xFF8E24AA, 0xFFD81B60, 0xFF6D4C41,
).map { it.toInt() }

/**
 * Tool rows (undo/redo + tool chips, brush types, colours, size/opacity, locked-layer warning).
 * Emits rows directly; call inside a Column.
 */
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
) {
    ToolRow(tool, onToolChange, canUndo, canRedo, onUndo, onRedo, onLayers)
    BrushRow(tool, brushType, onSelectBrush, onAddShadingLayer)
    SwatchRow(brushColor, onColorSelected)
    SizeRow(brushSize, onBrushSizeChange, brushAlpha, onBrushAlphaChange)
    val layer = selectedLayer
    if (layer != null && (layer.locked || !layer.visible)) {
        Text(
            "\"${layer.name}\" is ${if (layer.locked) "locked" else "hidden"} - drawing disabled",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun ToolRow(
    tool: Tool,
    onToolChange: (Tool) -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onLayers: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onUndo, enabled = canUndo) {
            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
        }
        IconButton(onClick = onRedo, enabled = canRedo) {
            Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
        }
        FilterChip(
            selected = tool == Tool.BRUSH,
            onClick = { onToolChange(Tool.BRUSH) },
            label = { Text("Brush") },
            modifier = Modifier.padding(start = 8.dp),
        )
        FilterChip(
            selected = tool == Tool.ERASER,
            onClick = { onToolChange(Tool.ERASER) },
            label = { Text("Eraser") },
            modifier = Modifier.padding(start = 8.dp),
        )
        Box(Modifier.weight(1f))
        IconButton(onClick = onLayers) { Icon(Icons.Default.Layers, contentDescription = "Layers") }
    }
}

@Composable
private fun SwatchRow(brushColor: Int, onColorSelected: (Int) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        items(SWATCHES) { argb ->
            val selected = brushColor == argb
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color(argb))
                    .border(
                        width = if (selected) 3.dp else 1.dp,
                        color = if (selected) MaterialTheme.colorScheme.primary else Color.Gray,
                        shape = CircleShape,
                    )
                    .clickable { onColorSelected(argb) },
            )
        }
    }
}

@Composable
private fun BrushRow(
    tool: Tool,
    brushType: BrushType,
    onSelectBrush: (BrushType) -> Unit,
    onAddShadingLayer: () -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        item { TextButton(onClick = onAddShadingLayer) { Text("+ Shading layer") } }
        items(BrushType.values().toList()) { type ->
            FilterChip(
                selected = tool == Tool.BRUSH && brushType == type,
                onClick = { onSelectBrush(type) },
                label = { Text(type.label) },
            )
        }
    }
}

@Composable
private fun SizeRow(
    brushSize: Float,
    onBrushSizeChange: (Float) -> Unit,
    brushAlpha: Float,
    onBrushAlphaChange: (Float) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Size ${brushSize.roundToInt()}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(56.dp))
        Slider(value = brushSize, onValueChange = onBrushSizeChange, valueRange = 1f..40f, modifier = Modifier.weight(1f))
        Text("Op ${(brushAlpha * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(56.dp).padding(start = 4.dp))
        Slider(value = brushAlpha, onValueChange = onBrushAlphaChange, valueRange = 0.05f..1f, modifier = Modifier.weight(1f))
    }
}
