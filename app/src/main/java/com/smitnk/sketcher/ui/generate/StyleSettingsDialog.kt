@file:OptIn(ExperimentalMaterial3Api::class)

package com.smitnk.sketcher.ui.generate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.smitnk.sketcher.image.DrawMode
import com.smitnk.sketcher.image.GeneratorSettings
import kotlin.math.roundToInt

private const val MAX_SPACING = 12f
private const val MIN_SPACING = 3f

@Composable
fun StyleSettingsDialog(
    current: GeneratorSettings,
    onDismiss: () -> Unit,
    onApply: (GeneratorSettings) -> Unit,
) {
    var detail by remember { mutableFloatStateOf(current.detail) }
    var mode by remember { mutableStateOf(current.mode) }
    var fillColors by remember { mutableFloatStateOf(current.fillColors.toFloat().coerceIn(6f, 16f)) }
    var fillSpacing by remember { mutableFloatStateOf(current.fillSpacing.coerceIn(3f, 10f)) }
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
                Text("Mode")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (m in DrawMode.values()) {
                        FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m.label) })
                    }
                }
                if (mode == DrawMode.COLOUR_FILL) {
                    Text("Fill colours: ${fillColors.roundToInt()}")
                    Slider(value = fillColors, onValueChange = { fillColors = it }, valueRange = 6f..16f, steps = 9)
                    Text("Fill stroke spacing: ${"%.1f".format(fillSpacing)} px")
                    Slider(value = fillSpacing, onValueChange = { fillSpacing = it }, valueRange = 3f..10f)
                }
                if (mode == DrawMode.OUTLINE_SHADING) {
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
                        mode = mode,
                        includeShading = mode == DrawMode.OUTLINE_SHADING,
                        fillColors = fillColors.roundToInt().coerceIn(6, 16),
                        fillSpacing = fillSpacing,
                        hatchSpacing = MAX_SPACING - (MAX_SPACING - MIN_SPACING) * density,
                        shadingLevels = levels.roundToInt().coerceIn(1, 4),
                    ),
                )
            }) { Text("Apply & generate") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Adapter for the new pipeline: same call shape as [StylePickerSheet]. EditorScreen can switch to this
 * (or call [StylePickerSheet] directly) once the ViewModel holds a PipelineSettings.
 */
@Composable
fun StyleSettingsDialog(
    settings: com.smitnk.sketcher.image.pipeline.PipelineSettings,
    onSettingsChange: (com.smitnk.sketcher.image.pipeline.PipelineSettings) -> Unit,
    onGenerate: () -> Unit,
    onDismiss: () -> Unit,
    sourceThumbnail: androidx.compose.ui.graphics.ImageBitmap? = null,
) = StylePickerSheet(settings, onSettingsChange, onGenerate, onDismiss, sourceThumbnail)
