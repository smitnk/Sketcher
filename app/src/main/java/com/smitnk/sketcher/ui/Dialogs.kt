@file:OptIn(ExperimentalMaterial3Api::class)

package com.smitnk.sketcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.smitnk.sketcher.export.ExportFormat
import com.smitnk.sketcher.image.GeneratorSettings
import kotlin.math.roundToInt

private const val MAX_SPACING = 12f
private const val MIN_SPACING = 3f

@Composable
fun SettingsDialog(
    current: GeneratorSettings,
    onDismiss: () -> Unit,
    onApply: (GeneratorSettings) -> Unit,
) {
    var detail by remember { mutableFloatStateOf(current.detail) }
    var shading by remember { mutableStateOf(current.includeShading) }
    var density by remember {
        mutableFloatStateOf(((MAX_SPACING - current.hatchSpacing) / (MAX_SPACING - MIN_SPACING)).coerceIn(0f, 1f))
    }
    var levels by remember { mutableFloatStateOf(current.shadingLevels.toFloat()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Generation settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Detail (more edges)")
                Slider(value = detail, onValueChange = { detail = it })
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Shading layer", modifier = Modifier.weight(1f))
                    Switch(checked = shading, onCheckedChange = { shading = it })
                }
                if (shading) {
                    Text("Shading density")
                    Slider(value = density, onValueChange = { density = it })
                    Text("Shading levels: ${levels.roundToInt()}")
                    Slider(value = levels, onValueChange = { levels = it }, valueRange = 1f..4f, steps = 2)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onApply(
                    current.copy(
                        detail = detail,
                        includeShading = shading,
                        hatchSpacing = MAX_SPACING - (MAX_SPACING - MIN_SPACING) * density,
                        shadingLevels = levels.roundToInt().coerceIn(1, 4),
                    ),
                )
            }) { Text("Apply & generate") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ExportDialog(
    selectedLayerName: String,
    onDismiss: () -> Unit,
    onExport: (scale: Int, format: ExportFormat, transparent: Boolean, layerOnly: Boolean) -> Unit,
) {
    var scale by remember { mutableIntStateOf(1) }
    var format by remember { mutableStateOf(ExportFormat.PNG) }
    var transparent by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Resolution")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = scale == 1, onClick = { scale = 1 }, label = { Text("x1") })
                    FilterChip(selected = scale == 2, onClick = { scale = 2 }, label = { Text("x2") })
                }
                Text("Format")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = format == ExportFormat.PNG,
                        onClick = { format = ExportFormat.PNG },
                        label = { Text("PNG") },
                    )
                    FilterChip(
                        selected = format == ExportFormat.JPEG,
                        onClick = { format = ExportFormat.JPEG },
                        label = { Text("JPEG (white)") },
                    )
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Transparent background (PNG)", modifier = Modifier.weight(1f))
                    Switch(
                        checked = transparent && format == ExportFormat.PNG,
                        onCheckedChange = { transparent = it },
                        enabled = format == ExportFormat.PNG,
                    )
                }
                OutlinedButton(
                    onClick = { onExport(scale, ExportFormat.PNG, true, true) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Export only \"$selectedLayerName\" (transparent PNG)") }
            }
        },
        confirmButton = {
            TextButton(onClick = { onExport(scale, format, transparent, false) }) { Text("Export") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
