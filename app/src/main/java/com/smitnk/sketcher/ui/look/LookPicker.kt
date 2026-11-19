package com.smitnk.sketcher.ui.look

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.smitnk.sketcher.look.CameraFollow
import com.smitnk.sketcher.look.LookSettings
import com.smitnk.sketcher.look.Misregister
import com.smitnk.sketcher.look.Paper
import com.smitnk.sketcher.look.PaperTexture
import com.smitnk.sketcher.look.PenStyle
import com.smitnk.sketcher.look.PostFilter
import com.smitnk.sketcher.look.PostFilters
import com.smitnk.sketcher.look.StrokeFeel
import com.smitnk.sketcher.ui.theme.SketcherTokens
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * "Look" section: a row of preset cards ([LookSettings.PRESETS], each with a small procedurally drawn preview)
 * followed by the advanced toggles (paper, misregistration, post filter, on twos). Used inside the Style sheet and
 * in the editor's "Look" overflow sheet. Stateless: [onChange] gets the new settings.
 */
@Composable
fun LookPicker(look: LookSettings, onChange: (LookSettings) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(SketcherTokens.space12)) {
        Text("Look", style = MaterialTheme.typography.titleSmall)
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(SketcherTokens.space8),
        ) {
            for ((name, preset) in LookSettings.PRESETS) {
                val applied = preset.copy(seed = look.seed)
                LookCard(name, preset, selected = look == applied, onClick = { onChange(applied) })
            }
        }

        Text("Paper", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ChipRow(
            listOf(Paper.PLAIN to "Plain", Paper.CREAM to "Cream", Paper.PENCIL_TOOTH to "Pencil tooth", Paper.ENGRAVING to "Engraving"),
            look.paper,
        ) { onChange(look.copy(paper = it)) }

        ToggleRow("Misregistration", "Colour fill sits a pixel or two off the outline", look.misregisterColour) {
            onChange(look.copy(misregisterColour = it))
        }

        Text("Post filter", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ChipRow(
            listOf(
                PostFilter.NONE to "None",
                PostFilter.ONE_BIT_ATKINSON to "1-bit Atkinson",
                PostFilter.ONE_BIT_BAYER to "1-bit Bayer",
                PostFilter.HALFTONE to "Halftone",
            ),
            look.post,
        ) { onChange(look.copy(post = it)) }

        ToggleRow("On twos", "Redraw 12 times a second in exported video", look.onTwos) { onChange(look.copy(onTwos = it)) }

        MotionSection(look, onChange)
    }
}

/** "Motion": pen sprite at the tip and the follow camera. Playback and MP4/GIF export look the same. */
@Composable
private fun MotionSection(look: LookSettings, onChange: (LookSettings) -> Unit) {
    Text("Motion", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    ToggleRow("Show pen while drawing", "A pen follows the line, lifting between strokes", look.showPen) {
        onChange(look.copy(showPen = it))
    }
    if (look.showPen) {
        ChipRow(
            listOf(PenStyle.PENCIL to "Pencil", PenStyle.MARKER to "Marker", PenStyle.BRUSH to "Brush"),
            look.penStyle,
        ) { onChange(look.copy(penStyle = it)) }
    }
    ToggleRow("Camera follows pen", "Zooms in on the pen, then shows the whole drawing at the end", look.cameraFollow) {
        onChange(look.copy(cameraFollow = it))
    }
    if (look.cameraFollow) {
        var zoom by remember(look.cameraZoom) { mutableFloatStateOf(look.cameraZoom) }
        val label = "%.1fx".format(java.util.Locale.US, zoom)
        Row(Modifier.fillMaxWidth().heightIn(min = SketcherTokens.minTouchTarget), verticalAlignment = Alignment.CenterVertically) {
            Text("Zoom", style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(56.dp))
            Slider(
                value = zoom,
                onValueChange = { zoom = (it * 10f).roundToInt() / 10f },
                onValueChangeFinished = { onChange(look.copy(cameraZoom = zoom.coerceIn(CameraFollow.ZOOM_MIN, CameraFollow.ZOOM_MAX))) },
                valueRange = CameraFollow.ZOOM_MIN..CameraFollow.ZOOM_MAX,
                modifier = Modifier.weight(1f).semantics { contentDescription = "Camera zoom"; stateDescription = label },
            )
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(48.dp), textAlign = TextAlign.End)
        }
    }
}

@Composable
private fun <T> ChipRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(SketcherTokens.space8)) {
        for ((value, name) in options) {
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(name, style = MaterialTheme.typography.labelLarge) },
            )
        }
    }
}

@Composable
private fun ToggleRow(title: String, hint: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = SketcherTokens.minTouchTarget)
            .selectable(selected = checked, role = Role.Switch, onClick = { onChecked(!checked) }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun LookCard(name: String, preset: LookSettings, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.width(96.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) scheme.primaryContainer else scheme.surfaceContainerLowest,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) scheme.primary else scheme.outlineVariant),
    ) {
        Column(
            Modifier.padding(SketcherTokens.space8),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(SketcherTokens.space4),
        ) {
            Canvas(Modifier.fillMaxWidth().height(64.dp).clearAndSetSemantics { }) { drawLookPreview(preset) }
            Text(
                name,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                color = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
            )
        }
    }
}

/** Tiny procedural sample (ball, colour fill, hatch) drawn with the preset's paper, misregistration and post filter. */
private fun DrawScope.drawLookPreview(look: LookSettings) {
    val w = size.width
    val h = size.height
    val ink = Color(0xFF22262B)
    val fill = Color(0xFFD9573B)
    drawRect(Color(PaperTexture.baseColor(look.paper)))
    if (look.paper == Paper.PENCIL_TOOTH || look.paper == Paper.CREAM) {
        // a few deterministic tooth specks
        for (i in 0 until 90) {
            val x = PaperTexture.hash(look.seed, i, 1) * w
            val y = PaperTexture.hash(look.seed, i, 2) * h
            drawCircle(Color(0x14000000), 0.7f + PaperTexture.hash(look.seed, i, 3), Offset(x, y))
        }
    }
    val c = Offset(w / 2f, h / 2f)
    val r = h * 0.36f
    val (mx, my) = if (look.misregisterColour) Misregister.offset(look.seed).let { it.first * 1.6f to it.second * 1.6f } else 0f to 0f
    val lineW = if (look.feel == StrokeFeel.PEN_PLOTTER) 1f else 2.2f
    if (look.post == PostFilter.NONE) {
        drawCircle(fill, r * 0.8f, c + Offset(mx, my))
        drawCircle(ink, r, c, style = Stroke(lineW))
        val jitter = if (look.feel == StrokeFeel.SCRATCH_BOIL) 1.6f else 0f
        for (i in -2..2) {
            val x = c.x + i * r * 0.28f
            drawLine(ink, Offset(x - r * 0.2f, c.y + r * 1.15f + jitter * (i and 1)), Offset(x + r * 0.2f, c.y + r * 1.15f - jitter), lineW, StrokeCap.Round)
        }
    } else {
        // Dither / halftone preview: shade a ball with a light from the top left, quantised like the real filters.
        val step = if (look.post == PostFilter.HALFTONE) 5f else 3f
        var y = 0f
        var iy = 0
        while (y < h) {
            var x = 0f
            var ix = 0
            while (x < w) {
                val dx = (x + step / 2 - c.x) / r
                val dy = (y + step / 2 - c.y) / r
                val d2 = dx * dx + dy * dy
                val gray = if (d2 > 1f) 0.95f else (0.25f + 0.65f * (0.5f + 0.5f * (-dx * 0.6f - dy * 0.6f + sqrt(1f - d2) * 0.5f)).coerceIn(0f, 1f))
                if (look.post == PostFilter.HALFTONE) {
                    val rad = (1f - gray) * step * 0.75f
                    // 45 degree grid: offset every other row by half a cell
                    drawCircle(ink, rad, Offset(x + step / 2 + if (iy % 2 == 0) 0f else step / 2, y + step / 2))
                } else if (gray < PostFilters.bayerThreshold(ix, iy)) {
                    drawRect(ink, Offset(x, y), Size(step, step))
                }
                x += step; ix++
            }
            y += step; iy++
        }
    }
}

/** Stand-alone bottom sheet with the [LookPicker] (editor overflow "Look"). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LookSheet(look: LookSettings, onChange: (LookSettings) -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = SketcherTokens.elevationSheet,
    ) {
        LookPicker(
            look,
            onChange,
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SketcherTokens.screenMargin)
                .padding(bottom = SketcherTokens.space16),
        )
    }
}

