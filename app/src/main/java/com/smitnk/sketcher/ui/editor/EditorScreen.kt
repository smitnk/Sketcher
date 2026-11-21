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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import com.smitnk.sketcher.image.pipeline.SketchPipeline
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.smitnk.sketcher.export.AnimExportKind
import com.smitnk.sketcher.export.ExportFormat
import com.smitnk.sketcher.image.pipeline.PipelineSettings
import com.smitnk.sketcher.ui.EditorEvent
import com.smitnk.sketcher.ui.EditorOverlay
import com.smitnk.sketcher.ui.EditorOverlaySaver
import com.smitnk.sketcher.ui.PendingExport
import com.smitnk.sketcher.ui.PendingExportKind
import com.smitnk.sketcher.ui.PendingExportSaver
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
import com.smitnk.sketcher.ui.frames.FrameActionsSheet
import com.smitnk.sketcher.ui.frames.FrameStrip
import com.smitnk.sketcher.export.FramesExportKind
import com.smitnk.sketcher.model.FlipbookClock
import com.smitnk.sketcher.model.FrameOps
import com.smitnk.sketcher.model.AddFrameKind
import com.smitnk.sketcher.ui.export.ExportProgressDialog
import com.smitnk.sketcher.ui.generate.StylePickerSheet
import com.smitnk.sketcher.ui.layers.LayersPanel
import com.smitnk.sketcher.ui.settings.MlSettingsSheet
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
    // Exactly one overlay at a time; back closes it.
    var overlay by rememberSaveable(stateSaver = EditorOverlaySaver) { mutableStateOf<EditorOverlay>(EditorOverlay.None) }
    // Style sheet edits a draft copy; it is committed only by "Draw it" (dismiss discards it).
    var styleDraft by remember { mutableStateOf<PipelineSettings?>(null) }
    var styleFromMl by remember { mutableStateOf(false) } // MlSettings opened from the style sheet -> return to it
    val openStyle: () -> Unit = {
        styleDraft = vm.pipelineSettings
        overlay = EditorOverlay.Style
    }
    var showLook by remember { mutableStateOf(false) }
    val openLayers = { overlay = EditorOverlay.Layers }
    val openMl: () -> Unit = {
        styleFromMl = overlay == EditorOverlay.Style
        vm.openMlSettings()
        overlay = EditorOverlay.MlSettings
    }
    val closeOverlay: () -> Unit = {
        if (overlay == EditorOverlay.MlSettings) {
            vm.closeMlSettings()
            vm.dismissNnapiWarning()
            overlay = if (styleFromMl) EditorOverlay.Style else EditorOverlay.None
            styleFromMl = false
        } else {
            if (overlay == EditorOverlay.Style) styleDraft = null
            overlay = EditorOverlay.None
        }
    }
    BackHandler(enabled = overlay != EditorOverlay.None) { closeOverlay() }

    // Layer property edits (visibility, opacity, order...) made in the panel trigger autosave.
    LaunchedEffect(vm) { vm.watchLayerChanges() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.loadImage(uri)
    }
    val launchPicker = {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    // Frames: add from one photo / pose render, or several photos at once (each becomes a generated frame).
    var stripExpanded by rememberSaveable { mutableStateOf(false) }
    var framePickIsPose by remember { mutableStateOf(false) }
    val framePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.addFrame(AddFrameKind.FROM_IMAGE, uri, pose = framePickIsPose)
    }
    val multiPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) vm.addFramesFromImages(uris)
    }
    val imageOnly = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)

    val context = LocalContext.current
    var pendingExport by rememberSaveable(stateSaver = PendingExportSaver) { mutableStateOf(PendingExport()) }
    val runPending: (PendingExport) -> Unit = { p ->
        when (p.kind) {
            PendingExportKind.NONE -> Unit
            PendingExportKind.LAYERS_SEPARATELY ->
                vm.exportLayersSeparately(p.scale, ExportFormat.valueOf(p.format), p.transparent)
            PendingExportKind.IMAGE ->
                vm.export(p.scale, ExportFormat.valueOf(p.format), p.transparent, p.layerOnly)
            PendingExportKind.ANIMATION -> vm.exportAnimation(AnimExportKind.valueOf(p.anim))
            PendingExportKind.FRAMES ->
                vm.exportFrames(runCatching { FramesExportKind.valueOf(p.anim) }.getOrDefault(FramesExportKind.PNG_SEQUENCE), p.scale)
        }
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Granted or not, export; Exporter falls back to the app folder when denied.
        val p = pendingExport
        pendingExport = PendingExport()
        runCatching { runPending(p) }
    }

    // One-shot events (style sheet after import, CI auto-actions). Events of an older project session are dropped.
    LaunchedEffect(vm) {
        vm.editorEvents.collect { e ->
            if (e.sessionId != vm.sessionId) return@collect
            when (e) {
                is EditorEvent.OpenStyle -> if (overlay == EditorOverlay.None) openStyle()
                is EditorEvent.ShowLayers -> overlay = EditorOverlay.Layers
                is EditorEvent.AutoExport -> vm.autoExport()
                is EditorEvent.AutoVideo -> vm.autoVideo()
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

    // Flipbook clock: steps the shown frame at the chosen fps while "Play frames" is on.
    LaunchedEffect(vm.flipbookPlaying) {
        if (vm.flipbookPlaying) {
            val clock = FlipbookClock(vm.flipbookFps)
            var last = withFrameNanos { it }
            while (vm.flipbookPlaying) {
                val now = withFrameNanos { it }
                clock.fps = vm.flipbookFps
                vm.flipbookAdvance(clock.advance((now - last) / 1_000_000_000f))
                last = now
            }
        }
    }
    // Starting the draw-along animation ends the flipbook (they share the canvas).
    val togglePlay = {
        vm.stopFlipbook()
        anim.togglePlay()
    }

    var chromeVisible by remember { mutableStateOf(true) }
    // Measured chrome heights: the paper is fitted between the top bar and the dock + playback bar.
    var topBarPx by remember { mutableIntStateOf(0) }
    var bottomBasePx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
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
            SketchCanvas(
                vm,
                Modifier.fillMaxSize(),
                insetTopPx = if (chromeVisible) topBarPx.toFloat() else 0f,
                insetBottomPx = if (chromeVisible) bottomBasePx.toFloat() else 0f,
            )
        }

        if (!vm.hasProject && !vm.generating) {
            EmptyState(
                onImport = { launchPicker() },
                onDemo = { vm.runDemo() },
                modifier = Modifier.align(Alignment.Center),
            )
        }
        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(tween(SketcherTokens.MotionShort)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            TopBar(
                title = vm.projectName,
                onRename = { overlay = EditorOverlay.Rename(vm.projectName) },
                generating = vm.generating,
                playing = anim.playing,
                onBack = onBack,
                onStyle = { openStyle() },
                onLayers = { openLayers() },
                onTogglePlay = togglePlay,
                onExport = { overlay = EditorOverlay.Export },
                onImport = { launchPicker() },
                onDemo = { vm.runDemo() },
                onGenerate = { vm.generate() },
                onSettings = onOpenSettings,
                onProcessing = { openMl() },
                onLook = { showLook = true },
                modifier = Modifier
                    .onSizeChanged { topBarPx = it.height }
                    .statusBarsPadding()
                    .padding(horizontal = SketcherTokens.space12, vertical = SketcherTokens.space8),
            )
        }

        // Which engine drew the lines (AI / Cartoon / Classic), shown under the top bar after a generation.
        val engine = remember(vm.generationCount, vm.generating) {
            when (SketchPipeline.lastLineSource) {
                "AI" -> "AI line art"
                "CARTOON" -> "Cartoon lines"
                "CLASSIC" -> "Classic edges"
                "FLOW_LINES" -> "Flow lines"
                "FAST_EDGES" -> "Fast edges"
                else -> null
            }
        }
        if (chromeVisible && vm.hasProject && !vm.generating && vm.generationCount > 0 && engine != null) {
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(
                        top = with(density) { topBarPx.toDp() },
                        start = SketcherTokens.space16,
                    ),
            ) {
                AssistChip(
                    onClick = { openStyle() },
                    label = { Text("Lines: $engine", style = MaterialTheme.typography.labelMedium) },
                )
            }
        }

        (overlay as? EditorOverlay.Rename)?.let { rename ->
            val text = rename.text
            AlertDialog(
                onDismissRequest = { overlay = EditorOverlay.None },
                title = { Text("Rename project") },
                text = {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { overlay = EditorOverlay.Rename(it.take(80)) },
                        singleLine = true,
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = text.isNotBlank(),
                        onClick = {
                            vm.renameProject(text)
                            overlay = EditorOverlay.None
                        },
                    ) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { overlay = EditorOverlay.None }) { Text("Cancel") } },
            )
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .onSizeChanged { h ->
                    // Track the collapsed height only (panels open/close, dock hides while playing).
                    if (chromeVisible && !anim.playing && !vm.flipbookPlaying && h.height > 0) {
                        bottomBasePx = if (bottomBasePx == 0) h.height else minOf(bottomBasePx, h.height)
                    }
                }
                .navigationBarsPadding()
                .padding(horizontal = SketcherTokens.space12, vertical = SketcherTokens.space8),
            verticalArrangement = Arrangement.spacedBy(SketcherTokens.space8),
        ) {
            // Tools auto-hide while the animation plays for a clean view.
            AnimatedVisibility(
                visible = chromeVisible && !anim.playing && !vm.flipbookPlaying,
                enter = fadeIn(tween(SketcherTokens.MotionMedium)),
                exit = fadeOut(tween(200)),
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
                    onLayers = { openLayers() },
                    onAddShadingLayer = { vm.addShadingLayer() },
                    selectedLayer = vm.project.selectedLayer,
                )
            }
            AnimatedVisibility(
                visible = chromeVisible && !anim.playing,
                enter = fadeIn(tween(SketcherTokens.MotionShort)),
                exit = fadeOut(tween(200)),
            ) {
                FrameStrip(
                    project = vm.project,
                    expanded = stripExpanded,
                    onExpandedChange = { stripExpanded = it },
                    onionEnabled = vm.onionEnabled,
                    onOnionChange = { vm.onionEnabled = it },
                    onionOpacity = vm.onionOpacity,
                    onOnionOpacityChange = { vm.onionOpacity = it },
                    flipbookPlaying = vm.flipbookPlaying,
                    onToggleFlipbook = { vm.toggleFlipbook() },
                    fps = vm.flipbookFps,
                    onCycleFps = { vm.flipbookFps = FrameOps.nextFps(vm.flipbookFps) },
                    busy = vm.framesBusy,
                    onSelect = { vm.selectFrame(it) },
                    onFrameActions = { overlay = EditorOverlay.FrameActions(it) },
                    onAddBlank = { vm.addFrame(AddFrameKind.BLANK) },
                    onAddDuplicate = { vm.addFrame(AddFrameKind.DUPLICATE) },
                    onAddFromPhoto = {
                        framePickIsPose = false
                        framePicker.launch(imageOnly)
                    },
                    onAddFromPose = {
                        framePickIsPose = true
                        framePicker.launch(imageOnly)
                    },
                    onAddSeveral = { multiPicker.launch(imageOnly) },
                )
            }
            AnimatedVisibility(
                visible = chromeVisible,
                enter = fadeIn(tween(SketcherTokens.MotionShort)),
                exit = fadeOut(tween(200)),
            ) {
                PlaybackBar(
                    durationSec = anim.durationSec,
                    onDurationChange = { anim.durationSec = it },
                    onRestart = { vm.stopFlipbook(); anim.restart() },
                    onShowFinished = { anim.showFinished() },
                    playing = anim.playing,
                    onTogglePlay = togglePlay,
                    progress = anim.progress,
                    onSeek = { anim.seek(it) },
                    onSeekEnd = { if (anim.progress >= 1f && !anim.playing) anim.showFinished() },
                )
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

        // Last child so the dimming overlay covers the canvas and chrome and blocks touches.
        if (vm.generating || vm.batchProgress != null) {
            GeneratingIndicator(
                stage = vm.generationStageLabel,
                fraction = vm.generationDisplayProgress,
            )
        }
        // Opening a saved project: opaque cover (hides the old project / empty state, swallows touches) until Ready.
        if (vm.projectLoad == com.smitnk.sketcher.data.ProjectLoad.Loading) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(colors.canvasBackdrop)
                    .pointerInput(Unit) {
                        awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
                    },
                contentAlignment = Alignment.Center,
            ) { androidx.compose.material3.CircularProgressIndicator() }
        }
    }

    if (overlay == EditorOverlay.Layers) {
        LayersPanel(
            project = vm.project,
            onAddLayer = { vm.addLayer() },
            onSelectLayer = { vm.selectLayer(it) },
            onMoveLayer = { layer, delta -> vm.moveLayer(layer, delta) },
            onDeleteLayer = { vm.deleteLayer(it) },
            onDismiss = { closeOverlay() },
        )
    }

    (overlay as? EditorOverlay.FrameActions)?.let { a ->
        FrameActionsSheet(
            index = a.index,
            count = vm.project.frames.size,
            onDuplicate = { vm.duplicateFrame(a.index); overlay = EditorOverlay.None },
            onMove = { delta ->
                FrameOps.moveTarget(a.index, delta, vm.project.frames.size)?.let { to ->
                    vm.moveFrame(a.index, to)
                    overlay = EditorOverlay.FrameActions(to)
                }
            },
            onDelete = { overlay = EditorOverlay.DeleteFrame(a.index) },
            onDismiss = { closeOverlay() },
        )
    }

    (overlay as? EditorOverlay.DeleteFrame)?.let { d ->
        AlertDialog(
            onDismissRequest = { closeOverlay() },
            title = { Text("Delete frame ${d.index + 1}?") },
            text = { Text("Its drawing and photo are removed from this project. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteFrame(d.index); overlay = EditorOverlay.None }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { closeOverlay() }) { Text("Cancel") } },
        )
    }

    if (overlay == EditorOverlay.Style) {
        StylePickerSheet(
            settings = styleDraft ?: vm.pipelineSettings,
            onSettingsChange = { styleDraft = it },
            onGenerate = {
                if (!vm.generating) {
                    styleDraft?.let { vm.updatePipelineSettings(it) }
                    styleDraft = null
                    overlay = EditorOverlay.None
                    vm.generate()
                }
            },
            generating = vm.generating,
            onDismiss = { closeOverlay() },
            sourceThumbnail = vm.sourceThumbnail,
            models = vm.lineModels,
            selectedModelId = vm.selectedLineModelId,
            onSelectModel = { vm.selectLineModel(it) },
            onDownloadModel = { vm.downloadLineModel(it) },
            fastModels = vm.fastModels,
            selectedFastModelId = vm.fastModelId,
            onSelectFastModel = { vm.selectFastModel(it) },
            cartoonModels = vm.cartoonModels,
            selectedCartoonModelId = vm.cartoonModelId,
            onSelectCartoonModel = { vm.selectCartoonModel(it) },
            onOpenMlSettings = { openMl() },
            look = vm.lookSettings,
            onLookChange = { vm.setLook(it) },
        )
    }

    if (showLook) {
        com.smitnk.sketcher.ui.look.LookSheet(vm.lookSettings, { vm.setLook(it) }, { showLook = false })
    }

    if (overlay == EditorOverlay.MlSettings) {
        MlSettingsSheet(
            accelOptions = vm.accelOptions,
            selectedAccel = vm.selectedAccel,
            onSelectAccel = { vm.selectAccel(it) },
            benchmarkResults = vm.benchmarkResults,
            benchmarking = vm.benchmarking,
            onRunBenchmark = { vm.runBenchmark() },
            lastInferenceMs = vm.lastInferenceMs,
            activeAccelLabel = vm.activeAccelLabel,
            fallbackReason = vm.fallbackReason,
            onDismiss = { closeOverlay() },
            nnapiWarningPending = vm.nnapiWarningPending != null,
            onConfirmNnapi = { vm.confirmNnapiWarning() },
            onDismissNnapi = { vm.dismissNnapiWarning() },
        )
    }

    if (overlay == EditorOverlay.Export) {
        // Pre-Q devices need WRITE_EXTERNAL_STORAGE before export.
        val runWithPermission = { pending: PendingExport ->
            val needsPerm = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
            if (needsPerm) {
                pendingExport = pending
                permLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                runPending(pending)
            }
        }
        ExportDialog(
            selectedLayerName = vm.project.selectedLayer?.name ?: "layer",
            onDismiss = { overlay = EditorOverlay.None },
            durationSec = anim.durationSec,
            resolutionNote = "${vm.project.width} x ${vm.project.height} px",
            onExportLayersSeparately = { scale, format, transparent ->
                overlay = EditorOverlay.None
                runWithPermission(
                    PendingExport(PendingExportKind.LAYERS_SEPARATELY, scale, format.name, transparent),
                )
            },
            onExport = { scale, format, transparent, layerOnly ->
                overlay = EditorOverlay.None
                runWithPermission(
                    PendingExport(PendingExportKind.IMAGE, scale, format.name, transparent, layerOnly),
                )
            },
            onExportAnimation = { kind ->
                overlay = EditorOverlay.None
                runWithPermission(PendingExport(PendingExportKind.ANIMATION, anim = kind.name))
            },
            frameCount = vm.project.frames.size,
            flipbookFps = vm.flipbookFps,
            onExportFrames = { kind, fps ->
                overlay = EditorOverlay.None
                runWithPermission(PendingExport(PendingExportKind.FRAMES, fps, anim = kind.name))
            },
            blockedReason = vm.exportBlockedReason,
            workingWidth = vm.project.width,
            workingHeight = vm.project.height,
            originalWidth = vm.originalPhotoWidth,
            originalHeight = vm.originalPhotoHeight,
        )
    }

    vm.exportLabel?.let { label ->
        val saved = vm.lastSaved
        ExportProgressDialog(
            label = label,
            progress = vm.exportProgress,
            onCancel = { vm.cancelExport() },
            done = vm.exportDone,
            savedPath = saved?.path,
            savedUri = saved?.uri,
            savedMime = saved?.mime ?: "image/*",
            onDone = { vm.dismissExport() },
            title = vm.exportTitle,
        )
    }
}
