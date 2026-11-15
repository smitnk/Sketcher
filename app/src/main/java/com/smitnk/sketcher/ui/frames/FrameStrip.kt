@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.smitnk.sketcher.ui.frames

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.smitnk.sketcher.export.Renderer
import com.smitnk.sketcher.model.Frame
import com.smitnk.sketcher.model.FrameOps
import com.smitnk.sketcher.model.Project
import com.smitnk.sketcher.model.contentSignature
import com.smitnk.sketcher.ui.theme.LightExtraColors
import com.smitnk.sketcher.ui.theme.SketcherTokens
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.roundToInt

private val ThumbHeight = 56.dp

/** Thumbnail cache of the frame strip: one small bitmap per frame, re-rendered only when the frame changed. */
object FrameThumbs {
    private const val LONG_SIDE = 160

    /** Re-renders [frame]'s thumbnail if its content (or the canvas size) changed. Main thread only. */
    fun refresh(frame: Frame, width: Int, height: Int) {
        val sig = frame.contentSignature(withPhoto = true) * 31 + width * 4099L + height
        if (frame.thumbnail != null && frame.thumbSig == sig) return
        val k = LONG_SIDE.toFloat() / max(width, height).coerceAtLeast(1)
        val tw = (width * k).roundToInt().coerceAtLeast(1)
        val th = (height * k).roundToInt().coerceAtLeast(1)
        val bmp = try {
            Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        } catch (_: OutOfMemoryError) {
            return
        }
        val c = Canvas(bmp)
        c.drawColor(LightExtraColors.paper.toArgb()) // always light paper, both themes
        c.scale(k, k)
        c.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        Renderer.drawBackground(c, frame.background, frame.backgroundVisible, frame.backgroundOpacity, width, height)
        Renderer.drawLayers(c, width, height, frame.layers.toList())
        frame.thumbnail = bmp.asImageBitmap()
        frame.thumbSig = sig
    }
}

/**
 * Frame strip above the playback bar: counter + "Play frames" + add menu in the header, and (when expanded)
 * the onion-skin controls and a row of frame thumbnails. All edits go through the callbacks.
 */
@Composable
fun FrameStrip(
    project: Project,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onionEnabled: Boolean,
    onOnionChange: (Boolean) -> Unit,
    onionOpacity: Float,
    onOnionOpacityChange: (Float) -> Unit,
    flipbookPlaying: Boolean,
    onToggleFlipbook: () -> Unit,
    fps: Int,
    onCycleFps: () -> Unit,
    busy: Boolean,
    onSelect: (Int) -> Unit,
    onFrameActions: (Int) -> Unit,
    onAddBlank: () -> Unit,
    onAddDuplicate: () -> Unit,
    onAddFromPhoto: () -> Unit,
    onAddFromPose: () -> Unit,
    onAddSeveral: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val count = project.frames.size
    val current = project.currentFrameIndex.coerceIn(0, (count - 1).coerceAtLeast(0))
    val scheme = MaterialTheme.colorScheme
    var addMenu by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = SketcherTokens.space8),
        shape = MaterialTheme.shapes.large,
        color = SketcherTokens.colors.floatingSurface,
        contentColor = SketcherTokens.colors.onFloatingSurface,
        shadowElevation = SketcherTokens.elevationFloating,
        border = BorderStroke(1.dp, scheme.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = SketcherTokens.space4, vertical = SketcherTokens.space4)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { onExpandedChange(!expanded) },
                    modifier = Modifier
                        .defaultMinSize(minHeight = SketcherTokens.minTouchTarget)
                        .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" },
                ) {
                    Icon(
                        if (expanded) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                        contentDescription = if (expanded) "Collapse frames" else "Expand frames",
                    )
                    Text(
                        "Frames  ${FrameOps.counter(current, count)}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .padding(start = SketcherTokens.space4)
                            .semantics { contentDescription = "Frame ${current + 1} of $count" },
                    )
                }
                Box(Modifier.weight(1f))
                TextButton(
                    onClick = onCycleFps,
                    modifier = Modifier
                        .defaultMinSize(minHeight = SketcherTokens.minTouchTarget)
                        .semantics { contentDescription = "Flipbook speed $fps frames per second. Tap to change" },
                ) { Text("$fps fps", style = MaterialTheme.typography.labelLarge) }
                IconButton(
                    onClick = onToggleFlipbook,
                    modifier = Modifier.defaultMinSize(SketcherTokens.minTouchTarget, SketcherTokens.minTouchTarget),
                ) {
                    Icon(
                        if (flipbookPlaying) Icons.Default.Stop else Icons.Default.Slideshow,
                        contentDescription = if (flipbookPlaying) "Stop playing frames" else "Play frames",
                        tint = if (flipbookPlaying) scheme.primary else scheme.onSurface,
                    )
                }
                Box {
                    FilledIconButton(
                        onClick = { addMenu = true },
                        enabled = !busy,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = scheme.primary,
                            contentColor = scheme.onPrimary,
                        ),
                        modifier = Modifier.defaultMinSize(SketcherTokens.minTouchTarget, SketcherTokens.minTouchTarget),
                    ) { Icon(Icons.Default.Add, contentDescription = "Add frame") }
                    DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                        DropdownMenuItem(text = { Text("Blank frame") }, onClick = { addMenu = false; onAddBlank() })
                        DropdownMenuItem(text = { Text("Duplicate frame") }, onClick = { addMenu = false; onAddDuplicate() })
                        DropdownMenuItem(text = { Text("From photo") }, onClick = { addMenu = false; onAddFromPhoto() })
                        DropdownMenuItem(text = { Text("From pose render") }, onClick = { addMenu = false; onAddFromPose() })
                        DropdownMenuItem(text = { Text("Several photos…") }, onClick = { addMenu = false; onAddSeveral() })
                    }
                }
            }

            if (expanded) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = SketcherTokens.space8),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SketcherTokens.space8),
                ) {
                    FilterChip(
                        selected = onionEnabled,
                        onClick = { onOnionChange(!onionEnabled) },
                        label = { Text("Onion skin") },
                        modifier = Modifier.defaultMinSize(minHeight = SketcherTokens.minTouchTarget),
                    )
                    if (onionEnabled) {
                        Slider(
                            value = onionOpacity,
                            onValueChange = onOnionOpacityChange,
                            valueRange = 0.1f..0.6f,
                            modifier = Modifier
                                .weight(1f)
                                .semantics {
                                    contentDescription = "Onion skin strength"
                                    stateDescription = "${(onionOpacity * 100).roundToInt()} percent"
                                },
                            colors = SliderDefaults.colors(
                                thumbColor = scheme.primary,
                                activeTrackColor = scheme.primary,
                                inactiveTrackColor = scheme.surfaceContainerHighest,
                            ),
                        )
                    }
                }
                Thumbnails(project, current, count, busy, onSelect, onFrameActions)
            }
        }
    }
}

@Composable
private fun Thumbnails(
    project: Project,
    current: Int,
    count: Int,
    busy: Boolean,
    onSelect: (Int) -> Unit,
    onFrameActions: (Int) -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(current, count) {
        if (current in 0 until count) listState.animateScrollToItem((current - 1).coerceAtLeast(0))
    }
    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = SketcherTokens.space8,
            vertical = SketcherTokens.space4,
        ),
        horizontalArrangement = Arrangement.spacedBy(SketcherTokens.space8),
    ) {
        itemsIndexed(project.frames, key = { _, f -> f.id }) { index, frame ->
            FrameThumb(
                frame = frame,
                index = index,
                count = count,
                selected = index == current,
                aspect = project.width.toFloat() / project.height.coerceAtLeast(1),
                canvasW = project.width,
                canvasH = project.height,
                enabled = !busy,
                onClick = { onSelect(index) },
                onLongClick = { onFrameActions(index) },
            )
        }
    }
}

@Composable
private fun FrameThumb(
    frame: Frame,
    index: Int,
    count: Int,
    selected: Boolean,
    aspect: Float,
    canvasW: Int,
    canvasH: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    // Re-render when the frame's drawing changes; the open frame waits a moment so a stroke in progress is not repainted.
    val sig = frame.contentSignature(withPhoto = true)
    LaunchedEffect(sig, canvasW, canvasH) {
        if (selected && frame.thumbnail != null) delay(300)
        FrameThumbs.refresh(frame, canvasW, canvasH)
    }
    val w = (ThumbHeight.value * aspect).coerceIn(48f, 96f).dp
    val shape = MaterialTheme.shapes.small
    Box(
        Modifier
            .width(w)
            .height(ThumbHeight)
            .clip(shape)
            .background(LightExtraColors.paper)
            .combinedClickable(
                enabled = enabled,
                role = Role.Tab,
                onClickLabel = "Open frame ${index + 1}",
                onLongClickLabel = "Frame actions",
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .semantics(mergeDescendants = true) {
                this.selected = selected
                contentDescription = "Frame ${index + 1} of $count"
            },
    ) {
        frame.thumbnail?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.matchParentSize(),
            )
        }
        Text(
            "${index + 1}",
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) scheme.onPrimary else scheme.onSurface,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(SketcherTokens.space2)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(if (selected) scheme.primary else scheme.surfaceContainerHigh.copy(alpha = 0.85f))
                .padding(horizontal = SketcherTokens.space4),
        )
        // Selection ring drawn last so it sits above the picture.
        Box(
            Modifier
                .matchParentSize()
                .border(if (selected) 2.dp else 1.dp, if (selected) scheme.primary else scheme.outlineVariant, shape),
        )
    }
}
