@file:OptIn(ExperimentalMaterial3Api::class)

package com.smitnk.sketcher.ui.export

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.smitnk.sketcher.export.AnimExportKind
import com.smitnk.sketcher.export.ExportFormat
import kotlin.math.roundToInt

private enum class ExportMode { IMAGE, VIDEO, GIF }

@Composable
fun ExportDialog(
    selectedLayerName: String,
    onDismiss: () -> Unit,
    onExport: (scale: Int, format: ExportFormat, transparent: Boolean, layerOnly: Boolean) -> Unit,
    onExportAnimation: (AnimExportKind) -> Unit,
) {
    var mode by remember { mutableStateOf(ExportMode.IMAGE) }
    var scale by remember { mutableIntStateOf(1) }
    var format by remember { mutableStateOf(ExportFormat.PNG) }
    var transparent by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mode == ExportMode.IMAGE,
                        onClick = { mode = ExportMode.IMAGE },
                        label = { Text("Image (PNG/JPEG)") },
                    )
                    FilterChip(
                        selected = mode == ExportMode.VIDEO,
                        onClick = { mode = ExportMode.VIDEO },
                        label = { Text("Video MP4") },
                    )
                    FilterChip(
                        selected = mode == ExportMode.GIF,
                        onClick = { mode = ExportMode.GIF },
                        label = { Text("GIF") },
                    )
                }
                if (mode == ExportMode.VIDEO) {
                    Text("Draw-along animation as an MP4 video (30 fps, up to 1080 px, white background). Saved to Movies/Sketcher.")
                } else if (mode == ExportMode.GIF) {
                    Text("Draw-along animation as a looping GIF (12 fps, up to 540 px). Saved to Pictures/Sketcher.")
                } else {
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
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when (mode) {
                    ExportMode.IMAGE -> onExport(scale, format, transparent, false)
                    ExportMode.VIDEO -> onExportAnimation(AnimExportKind.VIDEO)
                    ExportMode.GIF -> onExportAnimation(AnimExportKind.GIF)
                }
            }) { Text("Export") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Shown while an MP4/GIF export runs; [progress] is 0..1. */
@Composable
fun ExportProgressDialog(label: String, progress: Float, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text("${(progress.coerceIn(0f, 1f) * 100f).roundToInt()}%")
            }
        },
        confirmButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
