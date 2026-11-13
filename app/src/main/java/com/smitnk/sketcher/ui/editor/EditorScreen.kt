package com.smitnk.sketcher.ui.editor

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smitnk.sketcher.ui.SketchViewModel
import com.smitnk.sketcher.ui.Tool
import com.smitnk.sketcher.ui.canvas.SketchCanvas
import com.smitnk.sketcher.ui.export.ExportDialog
import com.smitnk.sketcher.ui.export.ExportProgressDialog
import com.smitnk.sketcher.ui.generate.StyleSettingsDialog
import com.smitnk.sketcher.ui.layers.LayersPanel
import com.smitnk.sketcher.ui.theme.SketcherTokens
import kotlinx.coroutines.withTimeoutOrNull

/** Composition root of the editor: wires [SketchViewModel] state to the hoisted UI components. */
@Composable
fun EditorScreen(
    vm: SketchViewModel = viewModel(),
    onBack: () -> Unit = {},
    projectTitle: String = "Untitled sketch",
    onOpenSettings: (() -> Unit)? = null,
) {
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

    var chromeVisible by remember { mutableStateOf(true) }
    val colors = SketcherTokens.colors
    val tapSlop = LocalViewConfiguration.current.touchSlop
    BackHandler(enabled = !chromeVisible) { chromeVisible = true }

    Box(Modifier.fillMaxSize().background(colors.canvasBackdrop)) {
        // Chrome toggle observes (never consumes) pointer events above the canvas, so drawing keeps
        // working: only a two-finger tap, or a single tap in the outer side margins, toggles chrome.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(tapSlop) {
                    val edge = 28.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val starts = mutableMapOf(down.id to down.position)
                        var maxPointers = 1
                        var moved = false
                        val tapped = withTimeoutOrNull(300L) {
                            while (true) {
                                val e = awaitPointerEvent(PointerEventPass.Initial)
                                maxPointers = maxOf(maxPointers, e.changes.count { it.pressed })
                                e.changes.forEach { c ->
                                    val st = starts.getOrPut(c.id) { c.position }
                                    if ((c.position - st).getDistance() > tapSlop) moved = true
                                }
                                if (e.changes.none { it.pressed }) break
                            }
                            true
                        } ?: false
                        val onEdge = down.position.x < edge || down.position.x > size.width - edge
                        if (tapped && !moved && (maxPointers >= 2 || onEdge)) chromeVisible = !chromeVisible
                    }
                },
        ) {
            SketchCanvas(vm, Modifier.fillMaxSize())
        }

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

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(tween(SketcherTokens.MotionShort)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            TopBar(
                title = projectTitle,
                generating = vm.generating,
                playing = anim.playing,
                onBack = onBack,
                onStyle = { showSettings = true },
                onLayers = { showLayers = true },
                onTogglePlay = { anim.togglePlay() },
                onExport = { showExport = true },
                onImport = { launchPicker() },
                onDemo = { vm.runDemo() },
                onGenerate = { vm.generate() },
                onSettings = onOpenSettings,
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(horizontal = SketcherTokens.space12, vertical = SketcherTokens.space8),
            )
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = SketcherTokens.space12, vertical = SketcherTokens.space8),
            verticalArrangement = Arrangement.spacedBy(SketcherTokens.space8),
        ) {
            // Tools auto-hide while the animation plays for a clean view.
            AnimatedVisibility(
                visible = chromeVisible && !anim.playing,
                enter = fadeIn(tween(SketcherTokens.MotionMedium)),
                exit = fadeOut(tween(200)),
            ) {
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = colors.floatingSurface,
                    contentColor = colors.onFloatingSurface,
                    shadowElevation = SketcherTokens.elevationFloating,
                ) {
                    Column(
                        Modifier
                            .heightIn(max = 300.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = SketcherTokens.space12, vertical = SketcherTokens.space8),
                    ) {
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
            }
            AnimatedVisibility(
                visible = chromeVisible,
                enter = fadeIn(tween(SketcherTokens.MotionShort)),
                exit = fadeOut(tween(200)),
            ) {
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = colors.floatingSurface,
                    contentColor = colors.onFloatingSurface,
                    shadowElevation = SketcherTokens.elevationFloating,
                ) {
                    Column(Modifier.padding(horizontal = SketcherTokens.space8, vertical = SketcherTokens.space4)) {
                        PlaybackBar(
                            durationSec = anim.durationSec,
                            onDurationChange = { anim.durationSec = it },
                            onRestart = { anim.restart() },
                            onShowFinished = { anim.showFinished() },
                        )
                    }
                }
            }
        }

        // Small handle to bring the chrome back when hidden.
        AnimatedVisibility(
            visible = !chromeVisible,
            enter = fadeIn(tween(SketcherTokens.MotionShort)),
            exit = fadeOut(tween(SketcherTokens.MotionShort)),
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(SketcherTokens.space12),
        ) {
            Surface(
                shape = CircleShape,
                color = colors.floatingSurface,
                contentColor = colors.onFloatingSurface,
                shadowElevation = SketcherTokens.elevationFloating,
            ) {
                IconButton(onClick = { chromeVisible = true }) {
                    Icon(Icons.Default.ExpandLess, contentDescription = "Show controls")
                }
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
