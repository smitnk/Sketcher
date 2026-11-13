@file:OptIn(ExperimentalMaterial3Api::class)

package com.smitnk.sketcher.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.roundToInt

private val SWATCHES: List<Int> = listOf(
    0xFF000000, 0xFF555555, 0xFFFFFFFF, 0xFFE53935, 0xFFFB8C00, 0xFFFDD835,
    0xFF43A047, 0xFF00897B, 0xFF1E88E5, 0xFF8E24AA, 0xFFD81B60, 0xFF6D4C41,
).map { it.toInt() }

@Composable
fun SketchScreen(vm: SketchViewModel = viewModel()) {
    val anim = vm.anim
    var showLayers by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.loadImage(uri)
    }

    val context = LocalContext.current
    var pendingExport by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Granted or not, export; Exporter falls back to the app folder when denied.
        pendingExport?.invoke()
        pendingExport = null
    }

    LaunchedEffect(vm.generationCount) {
        if (vm.generationCount > 0) {
            if (vm.pendingShowLayers) {
                vm.pendingShowLayers = false
                showLayers = true
            }
            if (vm.pendingAutoExport) {
                vm.pendingAutoExport = false
                vm.autoExport()
            }
        }
    }

    // ~60 fps animation clock, only runs while playing.
    LaunchedEffect(anim.playing) {
        if (anim.playing) {
            var last = withFrameNanos { it }
            while (anim.playing) {
                val now = withFrameNanos { it }
                anim.advance((now - last) / 1_000_000_000f)
                last = now
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sketcher", style = MaterialTheme.typography.titleMedium) },
                actions = {
                    IconButton(onClick = {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Icon(Icons.Default.Image, contentDescription = "Import photo") }
                    IconButton(onClick = { vm.runDemo() }) {
                        Icon(Icons.Default.Face, contentDescription = "Try demo image")
                    }
                    IconButton(onClick = { vm.generate() }, enabled = !vm.generating) {
                        Icon(Icons.Default.AutoFixHigh, contentDescription = "Generate strokes")
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Generation settings")
                    }
                    IconButton(onClick = { anim.togglePlay() }) {
                        Icon(
                            if (anim.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (anim.playing) "Pause" else "Play",
                        )
                    }
                    IconButton(onClick = { showExport = true }) {
                        Icon(Icons.Default.FileDownload, contentDescription = "Export")
                    }
                },
            )
        },
        bottomBar = { BottomPanel(vm, onLayers = { showLayers = true }) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            SketchCanvas(vm, Modifier.fillMaxSize())
            if (!vm.hasProject && !vm.generating) {
                Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Import a photo or try the demo", style = MaterialTheme.typography.titleMedium)
                    Button(onClick = {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Text("Import photo") }
                    OutlinedButton(onClick = { vm.runDemo() }) { Text("Try demo image") }
                }
            }
            if (vm.generating) {
                Surface(
                    modifier = Modifier.align(Alignment.Center),
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = 6.dp,
                ) {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text("Generating strokes...", modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
        }
    }

    if (showLayers) LayersSheet(vm, onDismiss = { showLayers = false })

    if (showSettings) {
        SettingsDialog(
            current = vm.settings,
            onDismiss = { showSettings = false },
            onApply = {
                vm.settings = it
                showSettings = false
                vm.generate()
            },
        )
    }

    if (showExport) {
        ExportDialog(
            selectedLayerName = vm.project.selectedLayer?.name ?: "layer",
            onDismiss = { showExport = false },
            onExport = { scale, format, transparent, layerOnly ->
                showExport = false
                val run = { vm.export(scale, format, transparent, layerOnly) }
                val needsPerm = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                    context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                if (needsPerm) {
                    pendingExport = run
                    permLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                } else {
                    run()
                }
            },
            onExportAnimation = { kind ->
                showExport = false
                val run = { vm.exportAnimation(kind) }
                val needsPerm = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                    context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                if (needsPerm) {
                    pendingExport = run
                    permLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                } else {
                    run()
                }
            },
        )
    }

    vm.exportLabel?.let { label ->
        ExportProgressDialog(label, vm.exportProgress, onCancel = { vm.cancelExport() })
    }
}

@Composable
private fun BottomPanel(vm: SketchViewModel, onLayers: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp)) {
            AnimationRow(vm)
            ToolRow(vm, onLayers)
            SwatchRow(vm)
            SizeRow(vm)
            val layer = vm.project.selectedLayer
            if (layer != null && (layer.locked || !layer.visible)) {
                Text(
                    "\"${layer.name}\" is ${if (layer.locked) "locked" else "hidden"} - drawing disabled",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun AnimationRow(vm: SketchViewModel) {
    val anim = vm.anim
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { anim.restart() }) { Icon(Icons.Default.Replay, contentDescription = "Restart") }
        IconButton(onClick = { anim.showFinished() }) {
            Icon(Icons.Default.SkipNext, contentDescription = "Show finished")
        }
        Text("Time ${anim.durationSec.roundToInt()}s", style = MaterialTheme.typography.labelMedium)
        Slider(
            value = anim.durationSec,
            onValueChange = { anim.durationSec = it },
            valueRange = 2f..60f,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
    }
}

@Composable
private fun ToolRow(vm: SketchViewModel, onLayers: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { vm.undo() }, enabled = vm.canUndo) {
            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
        }
        IconButton(onClick = { vm.redo() }, enabled = vm.canRedo) {
            Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
        }
        FilterChip(
            selected = vm.tool == Tool.BRUSH,
            onClick = { vm.tool = Tool.BRUSH },
            label = { Text("Brush") },
            modifier = Modifier.padding(start = 8.dp),
        )
        FilterChip(
            selected = vm.tool == Tool.ERASER,
            onClick = { vm.tool = Tool.ERASER },
            label = { Text("Eraser") },
            modifier = Modifier.padding(start = 8.dp),
        )
        Box(Modifier.weight(1f))
        IconButton(onClick = onLayers) { Icon(Icons.Default.Layers, contentDescription = "Layers") }
    }
}

@Composable
private fun SwatchRow(vm: SketchViewModel) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        items(SWATCHES) { argb ->
            val selected = vm.brushColor == argb
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color(argb))
                    .border(
                        width = if (selected) 3.dp else 1.dp,
                        color = if (selected) MaterialTheme.colorScheme.primary else Color.Gray,
                        shape = CircleShape,
                    )
                    .clickable {
                        vm.brushColor = argb
                        vm.tool = Tool.BRUSH
                    },
            )
        }
    }
}

@Composable
private fun SizeRow(vm: SketchViewModel) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Size ${vm.brushSize.roundToInt()}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(56.dp))
        Slider(value = vm.brushSize, onValueChange = { vm.brushSize = it }, valueRange = 1f..40f, modifier = Modifier.weight(1f))
    }
}
