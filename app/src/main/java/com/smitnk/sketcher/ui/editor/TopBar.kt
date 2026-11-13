package com.smitnk.sketcher.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smitnk.sketcher.ui.theme.SketcherTokens

/**
 * Floating pill top bar: back, project title, labelled Style / Layers / Export actions (play lives in the playback bar) and an overflow menu
 * (Import / Demo / Regenerate / optional Settings). The title is hidden on narrow screens.
 */
@Composable
fun TopBar(
    title: String,
    generating: Boolean,
    playing: Boolean,
    onBack: () -> Unit,
    onStyle: () -> Unit,
    onLayers: () -> Unit,
    onTogglePlay: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onDemo: () -> Unit,
    onGenerate: () -> Unit,
    modifier: Modifier = Modifier,
    onSettings: (() -> Unit)? = null,
    onRename: (() -> Unit)? = null,
    onProcessing: (() -> Unit)? = null,
) {
    val colors = SketcherTokens.colors
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val showTitle = maxWidth >= 400.dp
        Surface(
            shape = CircleShape,
            color = colors.floatingSurface,
            contentColor = colors.onFloatingSurface,
            shadowElevation = SketcherTokens.elevationFloating,
            modifier = Modifier.height(64.dp).fillMaxWidth(),
        ) {
            Row(
                Modifier.padding(horizontal = SketcherTokens.space4),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to home")
                }
                if (showTitle) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = SketcherTokens.space4)
                            .let { m -> if (onRename != null) m.clickable(onClickLabel = "Rename project", onClick = onRename) else m },
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                TopAction(Icons.Default.Palette, "Style", onStyle)
                TopAction(Icons.Default.Layers, "Layers", onLayers)
                TopAction(Icons.Default.FileDownload, "Export", onExport, primary = true)
                Spacer(Modifier.width(SketcherTokens.space2))
                OverflowMenu(generating, onImport, onDemo, onGenerate, onSettings, onRename, onProcessing)
            }
        }
    }
}

/** Icon over an always-visible 11sp label, so nobody has to guess what a bare icon does. */
@Composable
private fun TopAction(icon: ImageVector, label: String, onClick: () -> Unit, primary: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .padding(horizontal = SketcherTokens.space2)
            .defaultMinSize(minWidth = 56.dp, minHeight = SketcherTokens.minTouchTarget)
            .clip(MaterialTheme.shapes.large)
            .background(if (primary) scheme.primary else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = SketcherTokens.space8, vertical = SketcherTokens.space4),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = if (primary) scheme.onPrimary else LocalContentColor.current)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (primary) scheme.onPrimary else scheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun OverflowMenu(
    generating: Boolean,
    onImport: () -> Unit,
    onDemo: () -> Unit,
    onGenerate: () -> Unit,
    onSettings: (() -> Unit)?,
    onRename: (() -> Unit)?,
    onProcessing: (() -> Unit)?,
) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Default.MoreVert, contentDescription = "More options")
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(
            text = { Text("Import photo") },
            leadingIcon = { Icon(Icons.Default.Image, contentDescription = null) },
            onClick = { open = false; onImport() },
        )
        DropdownMenuItem(
            text = { Text("Try demo image") },
            leadingIcon = { Icon(Icons.Default.Face, contentDescription = null) },
            onClick = { open = false; onDemo() },
        )
        DropdownMenuItem(
            text = { Text("Regenerate strokes") },
            enabled = !generating,
            leadingIcon = { Icon(Icons.Default.AutoFixHigh, contentDescription = null) },
            onClick = { open = false; onGenerate() },
        )
        if (onRename != null) {
            DropdownMenuItem(
                text = { Text("Rename project") },
                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                onClick = { open = false; onRename() },
            )
        }
        if (onProcessing != null) {
            DropdownMenuItem(
                text = { Text("Processing (GPU/CPU)") },
                leadingIcon = { Icon(Icons.Default.Memory, contentDescription = null) },
                onClick = { open = false; onProcessing() },
            )
        }
        if (onSettings != null) {
            DropdownMenuItem(
                text = { Text("Settings") },
                leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                onClick = { open = false; onSettings() },
            )
        }
    }
}
