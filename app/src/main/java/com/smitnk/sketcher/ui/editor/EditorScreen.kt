package com.smitnk.sketcher.ui.editor

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smitnk.sketcher.ui.SketchViewModel
import com.smitnk.sketcher.ui.Tool
import com.smitnk.sketcher.ui.canvas.SketchCanvas
import com.smitnk.sketcher.ui.export.ExportDialog
import com.smitnk.sketcher.ui.export.ExportProgressDialog
import com.smitnk.sketcher.ui.generate.StyleSettingsDialog
import com.smitnk.sketcher.ui.layers.LayersPanel

/** Composition root of the editor: wires [SketchViewModel] state to the hoisted UI components. */
@Composable
fun EditorScreen(vm: SketchViewModel = viewModel()) {
    val anim = vm.anim
    var showLayers by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.loadImage(uri)
    }
    val launchPicker = {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
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
            TopBar(
                generating = vm.generating,
                playing = anim.playing,
                onImport = { launchPicker() },
                onDemo = { vm.runDemo() },
                onGenerate = { vm.generate() },
                onSettings = { showSettings = true },
                onTogglePlay = { anim.togglePlay() },
                onExport = { showExport = true },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.navigationBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp)) {
                    PlaybackBar(
                        durationSec = anim.durationSec,
                        onDurationChange = { anim.durationSec = it },
                        onRestart = { anim.restart() },
                        onShowFinished = { anim.showFinished() },
                    )
                    ToolDock(
                        tool = vm.tool,
                        onToolChange = { vm.tool = it },
                        brushType = vm.brushType,
                        onSelectBrush = { vm.selectBrush(it) },
                        brushColor = vm.brushColor,
                        onColorSelected = {
                            vm.brushColor = it
                            vm.tool = Tool.BRUSH
                        },
                        brushSize = vm.brushSize,
                        onBrushSizeChange = { vm.brushSize = it },
                        brushAlpha = vm.brushAlpha,
                        onBrushAlphaChange = { vm.brushAlpha = it },
                        canUndo = vm.canUndo,
                        canRedo = vm.canRedo,
                        onUndo = { vm.undo() },
                        onRedo = { vm.redo() },
                        onLayers = { showLayers = true },
                        onAddShadingLayer = { vm.addShadingLayer() },
                        selectedLayer = vm.project.selectedLayer,
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            SketchCanvas(vm, Modifier.fillMaxSize())
            if (!vm.hasProject && !vm.generating) {
                EmptyState(
                    onImport = { launchPicker() },
                    onDemo = { vm.runDemo() },
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            if (vm.generating) {
                GeneratingIndicator(Modifier.align(Alignment.Center))
            }
        }
    }

    if (showLayers) {
        LayersPanel(
            project = vm.project,
            onAddLayer = { vm.addLayer() },
            onSelectLayer = { vm.selectLayer(it) },
            onMoveLayer = { layer, delta -> vm.moveLayer(layer, delta) },
            onDeleteLayer = { vm.deleteLayer(it) },
            onDismiss = { showLayers = false },
        )
    }

    if (showSettings) {
        StyleSettingsDialog(
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
        // Pre-Q devices need WRITE_EXTERNAL_STORAGE before export.
        val runWithPermission = { run: () -> Unit ->
            val needsPerm = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
            if (needsPerm) {
                pendingExport = run
                permLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                run()
            }
        }
        ExportDialog(
            selectedLayerName = vm.project.selectedLayer?.name ?: "layer",
            onDismiss = { showExport = false },
            onExport = { scale, format, transparent, layerOnly ->
                showExport = false
                runWithPermission { vm.export(scale, format, transparent, layerOnly) }
            },
            onExportAnimation = { kind ->
                showExport = false
                runWithPermission { vm.exportAnimation(kind) }
            },
        )
    }

    vm.exportLabel?.let { label ->
        ExportProgressDialog(label, vm.exportProgress, onCancel = { vm.cancelExport() })
    }
}
