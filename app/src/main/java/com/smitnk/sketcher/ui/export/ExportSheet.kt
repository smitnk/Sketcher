@file:OptIn(ExperimentalMaterial3Api::class)

package com.smitnk.sketcher.ui.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Gif
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.smitnk.sketcher.export.AnimExportKind
import com.smitnk.sketcher.export.ExportFormat
import com.smitnk.sketcher.export.Exporter
import com.smitnk.sketcher.export.FramesExportKind
import com.smitnk.sketcher.model.FrameOps
import com.smitnk.sketcher.ui.theme.SketcherTokens
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

private enum class ExportMode { IMAGE, VIDEO, GIF, FRAMES, SVG }
private enum class LayerScope { SEPARATE, SELECTED, ALL }

/**
 * Export bottom sheet (name kept for compatibility with EditorScreen).
 *
 * New optional params (all have defaults):
 * [durationSec] draw-along length shown for video/GIF; [resolutionNote] e.g. "1080 x 1350 px";
 * [onExportLayersSeparately] enables the "Each layer" scope (hidden-disabled when null).
 */
@Composable
fun ExportDialog(
    selectedLayerName: String,
    onDismiss: () -> Unit,
    onExport: (scale: Int, format: ExportFormat, transparent: Boolean, layerOnly: Boolean) -> Unit,
    onExportAnimation: (AnimExportKind) -> Unit,
    durationSec: Float? = null,
    resolutionNote: String? = null,
    onExportLayersSeparately: ((scale: Int, format: ExportFormat, transparent: Boolean) -> Unit)? = null,
    blockedReason: String? = null,
    workingWidth: Int = 0,
    workingHeight: Int = 0,
    originalWidth: Int = 0,
    originalHeight: Int = 0,
    frameCount: Int = 1,
    flipbookFps: Int = FrameOps.DEFAULT_FPS,
    onExportFrames: ((kind: FramesExportKind, fps: Int) -> Unit)? = null,
    /** "SVG (vector)"; the card is hidden when null. */
    onExportSvg: (() -> Unit)? = null,
) {
    var mode by remember { mutableStateOf(ExportMode.IMAGE) }
    var framesKind by remember { mutableStateOf(FramesExportKind.PNG_SEQUENCE) }
    var framesFps by remember { mutableIntStateOf(if (flipbookFps in FrameOps.FPS_CHOICES) flipbookFps else FrameOps.DEFAULT_FPS) }
    var scale by remember { mutableIntStateOf(1) }
    var format by remember { mutableStateOf(ExportFormat.PNG) }
    var transparent by remember { mutableStateOf(false) }
    var scope by remember { mutableStateOf(LayerScope.ALL) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val transparentEffective = (transparent && format == ExportFormat.PNG) || scope == LayerScope.SELECTED
    val destination = when {
        mode == ExportMode.VIDEO || (mode == ExportMode.FRAMES && framesKind == FramesExportKind.VIDEO) -> "Saved to Movies/Sketcher"
        mode == ExportMode.FRAMES && framesKind == FramesExportKind.PNG_SEQUENCE -> "Saved to Pictures/Sketcher/<project name>/"
        else -> "Saved to Pictures/Sketcher"
    }
    val exportLabel = when (mode) {
        ExportMode.IMAGE -> "Export ${format.extension.uppercase()}"
        ExportMode.SVG -> "Export SVG"
        ExportMode.VIDEO -> "Export MP4"
        ExportMode.GIF -> "Export GIF"
        ExportMode.FRAMES -> when (framesKind) {
            FramesExportKind.PNG_SEQUENCE -> "Export $frameCount PNGs"
            FramesExportKind.GIF -> "Export flipbook GIF"
            FramesExportKind.VIDEO -> "Export flipbook MP4"
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large.copy(
            bottomStart = androidx.compose.foundation.shape.CornerSize(0.dp),
            bottomEnd = androidx.compose.foundation.shape.CornerSize(0.dp),
        ),
        tonalElevation = SketcherTokens.elevationSheet,
    ) {
        Column(
            Modifier
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SketcherTokens.screenMargin)
                .padding(bottom = SketcherTokens.space16),
            verticalArrangement = Arrangement.spacedBy(SketcherTokens.space16),
        ) {
            Text(
                "Export",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )

            // Format cards, grouped: Image / Video / Layers / Vector
            SectionLabel("Still image")
            Row(horizontalArrangement = Arrangement.spacedBy(SketcherTokens.space8)) {
                FormatCard(
                    title = "Image",
                    subtitle = "PNG / JPEG",
                    icon = Icons.Outlined.Image,
                    selected = mode == ExportMode.IMAGE,
                    onClick = { mode = ExportMode.IMAGE },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.weight(2f))
            }
            SectionLabel("Animation")
            Row(horizontalArrangement = Arrangement.spacedBy(SketcherTokens.space8)) {
                FormatCard(
                    title = "Video",
                    subtitle = "MP4",
                    icon = Icons.Outlined.Videocam,
                    selected = mode == ExportMode.VIDEO,
                    onClick = { mode = ExportMode.VIDEO },
                    modifier = Modifier.weight(1f),
                )
                FormatCard(
                    title = "GIF",
                    subtitle = "Looping",
                    icon = Icons.Outlined.Gif,
                    selected = mode == ExportMode.GIF,
                    onClick = { mode = ExportMode.GIF },
                    modifier = Modifier.weight(1f),
                )
                if (onExportFrames != null) {
                    FormatCard(
                        title = "Frames",
                        subtitle = "$frameCount",
                        icon = Icons.Outlined.Slideshow,
                        selected = mode == ExportMode.FRAMES,
                        onClick = { mode = ExportMode.FRAMES },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (onExportSvg != null) {
                SectionLabel("Vector")
                Row(horizontalArrangement = Arrangement.spacedBy(SketcherTokens.space8)) {
                    FormatCard(
                        title = "SVG (vector)",
                        subtitle = "Scales to any size",
                        icon = Icons.Outlined.Image,
                        selected = mode == ExportMode.SVG,
                        onClick = { mode = ExportMode.SVG },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.weight(2f))
                }
            }

            if (mode == ExportMode.SVG) {
                Text(
                    "One SVG with a group per layer. Flat colour fills become shapes; pencil lines are embedded as pictures. " +
                        "If SVG cannot be made, a PNG is saved and you are told why.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (mode == ExportMode.SVG) {
                // handled above
            } else if (mode == ExportMode.FRAMES) {
                SectionLabel("Frames")
                val kinds = listOf(
                    FramesExportKind.PNG_SEQUENCE to "PNGs",
                    FramesExportKind.GIF to "GIF",
                    FramesExportKind.VIDEO to "MP4",
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    kinds.forEachIndexed { index, (value, label) ->
                        SegmentedButton(
                            selected = framesKind == value,
                            onClick = { framesKind = value },
                            shape = SegmentedButtonDefaults.itemShape(index, kinds.size),
                            modifier = Modifier.defaultMinSize(minHeight = SketcherTokens.minTouchTarget),
                        ) { Text(label, maxLines = 1) }
                    }
                }
                if (framesKind != FramesExportKind.PNG_SEQUENCE) {
                    SectionLabel("Speed")
                    Row(horizontalArrangement = Arrangement.spacedBy(SketcherTokens.space8)) {
                        FrameOps.FPS_CHOICES.forEach { f ->
                            FilterChip(
                                selected = framesFps == f,
                                onClick = { framesFps = f },
                                label = { Text("$f fps") },
                                modifier = Modifier.defaultMinSize(minHeight = SketcherTokens.minTouchTarget),
                            )
                        }
                    }
                }
                Text(
                    when (framesKind) {
                        FramesExportKind.PNG_SEQUENCE -> "One transparent PNG per frame, numbered frame_0001, frame_0002, ... in a folder named after the project"
                        FramesExportKind.GIF -> "Flipbook: each frame's finished drawing for 1/$framesFps s, looping, on paper"
                        FramesExportKind.VIDEO -> "Flipbook: each frame's finished drawing for 1/$framesFps s, paper background"
                    } + ". For the draw-along time-lapse of the open frame choose Video or GIF.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                InfoRow(label = "Frames", value = "$frameCount")
            } else if (mode == ExportMode.IMAGE) {
                SectionLabel("Format")
                Row(horizontalArrangement = Arrangement.spacedBy(SketcherTokens.space8)) {
                    FilterChip(
                        selected = format == ExportFormat.PNG,
                        onClick = { format = ExportFormat.PNG },
                        label = { Text("PNG") },
                        modifier = Modifier.defaultMinSize(minHeight = SketcherTokens.minTouchTarget),
                    )
                    FilterChip(
                        selected = format == ExportFormat.JPEG,
                        onClick = { format = ExportFormat.JPEG },
                        label = { Text("JPEG (paper)") },
                        modifier = Modifier.defaultMinSize(minHeight = SketcherTokens.minTouchTarget),
                    )
                }

                SectionLabel("Size")
                Row(horizontalArrangement = Arrangement.spacedBy(SketcherTokens.space8)) {
                    FilterChip(
                        selected = scale == 1,
                        onClick = { scale = 1 },
                        label = { Text("×1") },
                        modifier = Modifier.defaultMinSize(minHeight = SketcherTokens.minTouchTarget),
                    )
                    FilterChip(
                        selected = scale == 2,
                        onClick = { scale = 2 },
                        label = { Text("×2") },
                        modifier = Modifier.defaultMinSize(minHeight = SketcherTokens.minTouchTarget),
                    )
                    FilterChip(
                        selected = scale == 4,
                        onClick = { scale = 4 },
                        label = { Text("×4") },
                        modifier = Modifier.defaultMinSize(minHeight = SketcherTokens.minTouchTarget),
                    )
                    FilterChip(
                        selected = scale == Exporter.SCALE_ORIGINAL,
                        onClick = { scale = Exporter.SCALE_ORIGINAL },
                        label = { Text("Original") },
                        modifier = Modifier.defaultMinSize(minHeight = SketcherTokens.minTouchTarget),
                    )
                }
                if (workingWidth > 0 && workingHeight > 0) {
                    val (ow, oh) = Exporter.targetSize(workingWidth, workingHeight, scale, originalWidth, originalHeight)
                    Text(
                        "Output: $ow x $oh px" + if (scale != 1) ". Lines are re-drawn sharp at this size." else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                val pngOnly = format == ExportFormat.PNG
                Row(
                    Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = SketcherTokens.minTouchTarget)
                        .toggleable(
                            value = transparentEffective,
                            enabled = pngOnly && scope != LayerScope.SELECTED,
                            role = Role.Switch,
                            onValueChange = { transparent = it },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Transparent background", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            if (pngOnly) "Skips the paper colour" else "PNG only; JPEG uses paper",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = transparentEffective && pngOnly,
                        onCheckedChange = null,
                        enabled = pngOnly && scope != LayerScope.SELECTED,
                    )
                }

                SectionLabel("Layers")
                val options = listOf(
                    LayerScope.SEPARATE to "Each layer",
                    LayerScope.SELECTED to "Selected",
                    LayerScope.ALL to "All visible",
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    options.forEachIndexed { index, (value, label) ->
                        SegmentedButton(
                            selected = scope == value,
                            onClick = { scope = value },
                            enabled = value != LayerScope.SEPARATE || onExportLayersSeparately != null,
                            shape = SegmentedButtonDefaults.itemShape(index, options.size),
                            modifier = Modifier.defaultMinSize(minHeight = SketcherTokens.minTouchTarget),
                        ) { Text(label, maxLines = 1) }
                    }
                }
                Text(
                    when (scope) {
                        LayerScope.SEPARATE ->
                            if (onExportLayersSeparately != null) {
                                if (format == ExportFormat.JPEG) "Each layer separately; JPEG has no transparency, so each layer is saved on paper"
                                else "Each layer separately, one transparent file per layer"
                            }
                            else "Each layer separately is not available yet"
                        LayerScope.SELECTED -> "Selected layer only: \"$selectedLayerName\" as a transparent PNG (JPEG is not used here)"
                        LayerScope.ALL -> "All visible layers together"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val kindText = if (mode == ExportMode.VIDEO) {
                    "Draw-along animation, 30 fps, paper background"
                } else {
                    "Draw-along animation, 12 fps, looping"
                }
                InfoRow(
                    label = "Duration",
                    value = durationSec?.let { "%.1f s".format(it) } ?: "Matches your playback speed",
                )
                InfoRow(
                    label = "Resolution",
                    value = resolutionNote ?: if (mode == ExportMode.VIDEO) "Up to 1080 px long side" else "Up to 540 px long side",
                )
                Text(kindText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SketcherTokens.space8)) {
                Icon(Icons.Outlined.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(destination, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (blockedReason != null) {
                Text(blockedReason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            Button(
                enabled = blockedReason == null,
                onClick = {
                    when (mode) {
                        ExportMode.IMAGE -> when (scope) {
                            LayerScope.ALL -> onExport(scale, format, transparent && format == ExportFormat.PNG, false)
                            LayerScope.SELECTED -> onExport(scale, ExportFormat.PNG, true, true)
                            LayerScope.SEPARATE -> onExportLayersSeparately?.invoke(scale, format, transparent && format == ExportFormat.PNG)
                        }
                        ExportMode.VIDEO -> onExportAnimation(AnimExportKind.VIDEO)
                        ExportMode.GIF -> onExportAnimation(AnimExportKind.GIF)
                        ExportMode.FRAMES -> onExportFrames?.invoke(framesKind, framesFps)
                        ExportMode.SVG -> onExportSvg?.invoke()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) { Text(exportLabel, style = MaterialTheme.typography.titleMedium) }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FormatCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = modifier
            .defaultMinSize(minHeight = 96.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) scheme.primaryContainer else scheme.surfaceContainerLowest,
        ),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) scheme.primary else scheme.outlineVariant),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(SketcherTokens.space12),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(SketcherTokens.space4),
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
            )
            Text(title, style = MaterialTheme.typography.titleSmall, color = scheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

/**
 * Progress dialog with a sketchy animated pencil; [progress] is 0..1.
 *
 * Success state: when [done] is true it shows the saved location. If [savedUri] is non-null a
 * Share button fires Intent.ACTION_SEND ([savedMime]); otherwise only [savedPath] text is shown.
 * [onDone] dismisses the success state (defaults to [onCancel]).
 */
@Composable
fun ExportProgressDialog(
    label: String,
    progress: Float,
    onCancel: () -> Unit,
    done: Boolean = false,
    savedPath: String? = null,
    savedUri: Uri? = null,
    savedMime: String = "image/*",
    onDone: () -> Unit = onCancel,
    title: String? = null,
) {
    val p = progress.coerceIn(0f, 1f)
    val context = LocalContext.current
    Dialog(
        onDismissRequest = { if (done) onDone() },
        properties = DialogProperties(dismissOnBackPress = done, dismissOnClickOutside = done),
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = SketcherTokens.elevationSheet,
        ) {
            Column(
                Modifier.padding(SketcherTokens.space24),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(SketcherTokens.space16),
            ) {
                if (done) {
                    Icon(
                        if (title == null) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        tint = if (title == null) SketcherTokens.colors.success else MaterialTheme.colorScheme.error,
                        modifier = Modifier.height(48.dp),
                    )
                    Text(
                        title ?: "Export complete",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        savedPath ?: "Saved to your gallery",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    if (savedUri != null) {
                        Button(
                            onClick = { shareUri(context, savedUri, savedMime) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                        ) {
                            Icon(Icons.Outlined.Share, contentDescription = null)
                            Spacer(Modifier.padding(horizontal = SketcherTokens.space4))
                            Text("Share")
                        }
                        OutlinedButton(
                            onClick = onDone,
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = SketcherTokens.minTouchTarget),
                        ) { Text("Done") }
                    } else {
                        Button(
                            onClick = onDone,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                        ) { Text("Done") }
                    }
                } else {
                    Text(
                        label,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.semantics { heading() },
                    )
                    PencilProgress(
                        progress = p,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp)
                            .semantics {
                                contentDescription = "Export progress"
                                stateDescription = "${(p * 100).roundToInt()} percent"
                            },
                    )
                    Text(
                        "${(p * 100f).roundToInt()}%",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    TextButton(
                        onClick = onCancel,
                        modifier = Modifier.defaultMinSize(minHeight = SketcherTokens.minTouchTarget),
                    ) { Text("Cancel") }
                }
            }
        }
    }
}

private fun shareUri(context: Context, uri: Uri, mime: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
}

/** A hand-drawn wobbly line that is "sketched" up to [progress], with a pencil tip at its end. */
@Composable
private fun PencilProgress(progress: Float, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val animationsOn = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
    val transition = rememberInfiniteTransition(label = "pencil")
    val phaseAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "wobble",
    )
    val phase = if (animationsOn) phaseAnim else 0f
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val ink = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.primary
    val onAccent = MaterialTheme.colorScheme.onPrimary

    Canvas(modifier) {
        val pad = 16.dp.toPx()
        val w = size.width - pad * 2
        val midY = size.height / 2
        val amp = 5.dp.toPx()
        val steps = 80
        fun yAt(t: Float, ph: Float) = midY + amp * sin(t * 14f + ph) * (0.6f + 0.4f * sin(t * 5f))

        fun linePath(upTo: Float, ph: Float): Path {
            val path = Path()
            val n = (steps * upTo).toInt().coerceAtLeast(1)
            for (i in 0..n) {
                val t = (i.toFloat() / steps).coerceAtMost(upTo)
                val x = pad + w * t
                val y = yAt(t, ph)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            return path
        }

        val stroke = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        // dotted guide (full length, static)
        drawPath(linePath(1f, 0f), color = track, style = stroke)
        if (progress > 0f) {
            drawPath(linePath(progress, phase * 0.15f), color = ink, style = stroke)
        }
        // pencil tip
        val tx = pad + w * progress
        val ty = yAt(progress, phase * 0.15f)
        val body = Path().apply {
            moveTo(tx, ty)
            lineTo(tx + 8.dp.toPx(), ty - 6.dp.toPx())
            lineTo(tx + 26.dp.toPx(), ty - 24.dp.toPx())
            lineTo(tx + 32.dp.toPx(), ty - 18.dp.toPx())
            lineTo(tx + 14.dp.toPx(), ty + 0.dp.toPx())
            lineTo(tx + 6.dp.toPx(), ty + 8.dp.toPx())
            close()
        }
        drawPath(body, color = accent)
        drawLine(onAccent, Offset(tx + 12.dp.toPx(), ty - 8.dp.toPx()), Offset(tx + 28.dp.toPx(), ty - 22.dp.toPx()), strokeWidth = 1.5.dp.toPx())
        drawCircle(ink, radius = 2.dp.toPx(), center = Offset(tx, ty))
    }
}
