@file:OptIn(ExperimentalMaterial3Api::class)

package com.smitnk.sketcher.ui.editor

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable

@Composable
fun TopBar(
    generating: Boolean,
    playing: Boolean,
    onImport: () -> Unit,
    onDemo: () -> Unit,
    onGenerate: () -> Unit,
    onSettings: () -> Unit,
    onTogglePlay: () -> Unit,
    onExport: () -> Unit,
) {
    TopAppBar(
        title = { Text("Sketcher", style = MaterialTheme.typography.titleMedium) },
        actions = {
            IconButton(onClick = onImport) { Icon(Icons.Default.Image, contentDescription = "Import photo") }
            IconButton(onClick = onDemo) {
                Icon(Icons.Default.Face, contentDescription = "Try demo image")
            }
            IconButton(onClick = onGenerate, enabled = !generating) {
                Icon(Icons.Default.AutoFixHigh, contentDescription = "Generate strokes")
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Generation settings")
            }
            IconButton(onClick = onTogglePlay) {
                Icon(
                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (playing) "Pause" else "Play",
                )
            }
            IconButton(onClick = onExport) {
                Icon(Icons.Default.FileDownload, contentDescription = "Export")
            }
        },
    )
}
