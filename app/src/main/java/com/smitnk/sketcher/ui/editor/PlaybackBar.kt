package com.smitnk.sketcher.ui.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.smitnk.sketcher.ui.theme.SketcherTokens
import kotlin.math.roundToInt

private val DurationPresets = listOf(5, 10, 20, 30, 60)

private fun formatTime(sec: Float): String {
    val t = sec.roundToInt().coerceAtLeast(0)
    return "%d:%02d".format(t / 60, t % 60)
}

/**
 * Floating pill playback bar.
 *
 * New params (all defaulted so the old call site still compiles):
 * [playing]/[onTogglePlay] drive the big play/pause button, [progress] (0..1) feeds the scrubber,
 * [onSeek] is called continuously while dragging (controller.seek pauses and shows that progress),
 * [onSeekEnd] when the finger lifts (resumes playback if it was playing before the scrub).
 */
@Composable
fun PlaybackBar(
    durationSec: Float,
    onDurationChange: (Float) -> Unit,
    onRestart: () -> Unit,
    onShowFinished: () -> Unit,
    modifier: Modifier = Modifier,
    playing: Boolean = false,
    onTogglePlay: () -> Unit = {},
    progress: Float = 0f,
    onSeek: (Float) -> Unit = {},
    onSeekEnd: () -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    var wasPlaying by remember { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf(false) }
    val p = progress.coerceIn(0f, 1f)

    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = SketcherTokens.space8),
        shape = CircleShape,
        color = SketcherTokens.colors.floatingSurface,
        contentColor = SketcherTokens.colors.onFloatingSurface,
        shadowElevation = SketcherTokens.elevationFloating,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier.padding(horizontal = SketcherTokens.space4, vertical = SketcherTokens.space4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledIconButton(
                onClick = onTogglePlay,
                modifier = Modifier.size(56.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Icon(
                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (playing) "Pause" else "Play",
                    modifier = Modifier.size(32.dp),
                )
            }
            IconButton(onClick = onRestart, modifier = Modifier.size(SketcherTokens.minTouchTarget)) {
                Icon(Icons.Default.Replay, contentDescription = "Restart")
            }
            Column(Modifier.weight(1f).padding(horizontal = SketcherTokens.space4)) {
                Slider(
                    value = p,
                    onValueChange = {
                        if (!scrubbing) {
                            scrubbing = true
                            wasPlaying = playing
                        }
                        onSeek(it)
                    },
                    onValueChangeFinished = {
                        scrubbing = false
                        onSeekEnd()
                        if (wasPlaying && p < 1f) onTogglePlay()
                        wasPlaying = false
                    },
                    modifier = Modifier
                        .heightIn(min = 32.dp)
                        .semantics { contentDescription = "Animation progress" },
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                )
                Text(
                    formatTime(p * durationSec),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                        .semantics { contentDescription = "Elapsed ${formatTime(p * durationSec)} of ${formatTime(durationSec)}" },
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FilterChip(
                    selected = menuOpen,
                    onClick = { menuOpen = true },
                    label = { Text("${durationSec.roundToInt()}s", fontWeight = FontWeight.Bold) },
                    leadingIcon = { Icon(Icons.Default.Timer, contentDescription = "Animation length", modifier = Modifier.size(18.dp)) },
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.heightIn(min = SketcherTokens.minTouchTarget),
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    Column(Modifier.widthIn(min = 240.dp).padding(horizontal = SketcherTokens.space12)) {
                        Text("Animation length", style = MaterialTheme.typography.titleSmall)
                        Row(
                            Modifier.fillMaxWidth().padding(top = SketcherTokens.space8),
                            horizontalArrangement = Arrangement.spacedBy(SketcherTokens.space4),
                        ) {
                            DurationPresets.forEach { s ->
                                FilterChip(
                                    selected = durationSec.roundToInt() == s,
                                    onClick = { onDurationChange(s.toFloat()) },
                                    label = { Text("${s}s") },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        Slider(
                            value = durationSec,
                            onValueChange = onDurationChange,
                            valueRange = 2f..60f,
                            modifier = Modifier.semantics { contentDescription = "Animation duration" },
                        )
                        Text(
                            "${durationSec.roundToInt()} seconds",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            IconButton(onClick = onShowFinished, modifier = Modifier.size(SketcherTokens.minTouchTarget)) {
                Icon(Icons.Default.SkipNext, contentDescription = "Show finished")
            }
        }
    }
}
