@file:OptIn(ExperimentalMaterial3Api::class)

package com.smitnk.sketcher.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smitnk.sketcher.model.Layer

@Composable
fun LayersSheet(vm: SketchViewModel, onDismiss: () -> Unit) {
    val project = vm.project
    var renaming by remember { mutableStateOf<Layer?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Layers", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                FilledTonalButton(onClick = { vm.addLayer() }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Add layer")
                }
            }

            if (project.background != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Photo", modifier = Modifier.width(56.dp))
                    SmallIcon(
                        icon = if (project.backgroundVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        description = "Toggle photo",
                        onClick = { project.backgroundVisible = !project.backgroundVisible },
                    )
                    Slider(
                        value = project.backgroundOpacity,
                        onValueChange = { project.backgroundOpacity = it },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // Top of the stack first.
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(project.layers.reversed(), key = { it.id }) { layer ->
                    LayerRow(
                        vm = vm,
                        layer = layer,
                        selected = project.selectedLayer === layer,
                        onRename = { renaming = layer },
                    )
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
}

@Composable
private fun LayerRow(vm: SketchViewModel, layer: Layer, selected: Boolean, onRename: () -> Unit) {
    val container = if (selected) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceVariant
    Card(
        colors = CardDefaults.cardColors(containerColor = container),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { vm.selectLayer(layer) },
    ) {
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    layer.name,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                )
                SmallIcon(Icons.Default.Edit, "Rename", onRename)
                SmallIcon(Icons.Default.ArrowUpward, "Move up", { vm.moveLayer(layer, +1) })
                SmallIcon(Icons.Default.ArrowDownward, "Move down", { vm.moveLayer(layer, -1) })
                SmallIcon(Icons.Default.Delete, "Delete", { vm.deleteLayer(layer) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                SmallIcon(
                    if (layer.visible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    "Visibility",
                    { layer.visible = !layer.visible },
                )
                SmallIcon(
                    if (layer.locked) Icons.Default.Lock else Icons.Default.LockOpen,
                    "Lock",
                    { layer.locked = !layer.locked },
                )
                Checkbox(checked = layer.inAnimation, onCheckedChange = { layer.inAnimation = it })
                Text("Anim", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = layer.opacity,
                    onValueChange = { layer.opacity = it },
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SmallIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(20.dp))
    }
}
