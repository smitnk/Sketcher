package com.smitnk.sketcher.ui

import android.app.Application
import android.graphics.Bitmap
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smitnk.sketcher.anim.AnimationController
import com.smitnk.sketcher.export.AnimExportKind
import com.smitnk.sketcher.export.ExportFormat
import com.smitnk.sketcher.export.Exporter
import com.smitnk.sketcher.export.GifExporter
import com.smitnk.sketcher.export.VideoExporter
import com.smitnk.sketcher.image.BrushType
import com.smitnk.sketcher.image.DrawMode
import com.smitnk.sketcher.image.GeneratedDrawing
import com.smitnk.sketcher.image.GeneratorSettings
import com.smitnk.sketcher.image.ImageLoader
import com.smitnk.sketcher.image.Stroke
import com.smitnk.sketcher.image.pipeline.LineSource
import com.smitnk.sketcher.image.pipeline.OnnxLineArtModel
import com.smitnk.sketcher.image.pipeline.PipelineSettings
import com.smitnk.sketcher.image.pipeline.ShadingLevel
import com.smitnk.sketcher.image.pipeline.SketchPipeline
import com.smitnk.sketcher.model.Layer
import com.smitnk.sketcher.model.Project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.sqrt

enum class Tool { BRUSH, ERASER }

/** Result of the last successful export. [uri] is null when it cannot be shared (legacy storage / several files). */
class SavedExport(val path: String, val uri: Uri?, val mime: String)

private interface EditAction {
    fun undo()
    fun redo()
}

private class AddStrokeAction(val layer: Layer, val stroke: Stroke) : EditAction {
    override fun undo() {
        val i = layer.strokes.indexOfLast { it === stroke }
        if (i >= 0) layer.strokes.removeAt(i)
    }

    override fun redo() {
        layer.strokes.add(stroke)
    }
}

/** [removed] holds (index at time of removal, stroke) in removal order. */
private class EraseAction(val layer: Layer, val removed: List<Pair<Int, Stroke>>) : EditAction {
    override fun undo() {
        for (k in removed.indices.reversed()) {
            val (index, stroke) = removed[k]
            layer.strokes.add(index.coerceAtMost(layer.strokes.size), stroke)
        }
    }

    override fun redo() {
        for ((index, _) in removed) {
            if (index < layer.strokes.size) layer.strokes.removeAt(index)
        }
    }
}

class SketchViewModel(private val app: Application) : AndroidViewModel(app) {

    val project = Project()
    val anim = AnimationController()

    private var legacySettings by mutableStateOf(GeneratorSettings())

    /** Legacy settings (used by the settings dialog); changes are mapped onto [pipelineSettings]. */
    var settings: GeneratorSettings
        get() = legacySettings
        set(value) {
            legacySettings = value
            val p = pipelineSettings
            pipelineSettings = p.copy(
                detail = value.detail,
                shading = if (value.mode == DrawMode.OUTLINE_SHADING) {
                    if (p.shading == ShadingLevel.NONE) ShadingLevel.MEDIUM else p.shading
                } else {
                    ShadingLevel.NONE
                },
                colourFill = value.mode == DrawMode.COLOUR_FILL,
                fillColors = value.fillColors.coerceIn(6, 16),
                fillSpacing = value.fillSpacing,
            )
        }

    var pipelineSettings by mutableStateOf(PipelineSettings())
        private set

    fun updatePipelineSettings(s: PipelineSettings) {
        pipelineSettings = s
    }

    fun updatePipelineSettings(transform: (PipelineSettings) -> PipelineSettings) {
        pipelineSettings = transform(pipelineSettings)
    }

    var generationStage by mutableStateOf("")
        private set
    var generationProgress by mutableFloatStateOf(0f)
        private set

    private var lineArtModel: OnnxLineArtModel? = null

    private fun model(): OnnxLineArtModel =
        lineArtModel ?: OnnxLineArtModel(app).also { lineArtModel = it }

    override fun onCleared() {
        lineArtModel?.close()
        lineArtModel = null
        super.onCleared()
    }

    /** Parses intent extras: engine = auto|ai|cartoon|classic, shading = none|light|medium|heavy. */
    fun applyIntentOptions(engine: String?, shading: String?) {
        val src = when (engine?.lowercase()) {
            "auto" -> LineSource.AUTO
            "ai" -> LineSource.AI
            "cartoon" -> LineSource.CARTOON
            "classic" -> LineSource.CLASSIC
            else -> null
        }
        val sh = when (shading?.lowercase()) {
            "none" -> ShadingLevel.NONE
            "light" -> ShadingLevel.LIGHT
            "medium" -> ShadingLevel.MEDIUM
            "heavy" -> ShadingLevel.HEAVY
            else -> null
        }
        pipelineSettings = pipelineSettings.copy(
            lineSource = src ?: pipelineSettings.lineSource,
            shading = sh ?: pipelineSettings.shading,
        )
    }
    var generating by mutableStateOf(false)
        private set

    var tool by mutableStateOf(Tool.BRUSH)
    var brushColor by mutableIntStateOf(0xFF000000.toInt())
    var brushSize by mutableFloatStateOf(4f)
    var brushType by mutableStateOf(BrushType.PEN)
        private set
    var brushAlpha by mutableFloatStateOf(1f)

    /** Selects a brush and resets opacity to that brush's default. */
    fun selectBrush(type: BrushType) {
        brushType = type
        brushAlpha = type.defaultAlpha
        tool = Tool.BRUSH
    }

    /** Adds "Shading (manual)" directly above Outline, selects it, soft grey brush. */
    fun addShadingLayer() {
        val layer = project.newLayer("Shading (manual)")
        val o = project.layers.indexOfFirst { it.name == "Outline" }
        val at = if (o >= 0) o + 1 else (project.selectedIndex + 1).coerceIn(0, project.layers.size)
        project.layers.add(at, layer)
        project.selectedIndex = at
        selectBrush(BrushType.SOFT)
        brushColor = 0xFF555555.toInt()
        brushSize = 14f
    }

    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    /** Bumped on every live stroke change so the canvas redraws. */
    var liveTick by mutableIntStateOf(0)
        private set

    /** True once a photo/demo has been loaded or strokes generated (hides the empty state). */
    var hasProject by mutableStateOf(false)
        private set

    /** Bumped each time generation completes successfully. */
    var generationCount by mutableIntStateOf(0)
        private set
    var pendingShowLayers by mutableStateOf(false)
    var pendingAutoExport by mutableStateOf(false)
    private var pendingAutoVideo = false

    /** Set when a NEW photo was imported: the editor opens the style sheet, then clears it. */
    var styleSheetRequest by mutableStateOf(false)

    /** Small preview of the imported photo for the style sheet; computed once per source. */
    var sourceThumbnail by mutableStateOf<ImageBitmap?>(null)
        private set

    /** Last successful export (shown in the progress dialog once [exportDone]). */
    var lastSaved by mutableStateOf<SavedExport?>(null)
        private set
    var exportDone by mutableStateOf(false)
        private set

    /** Closes the export progress/result dialog. */
    fun dismissExport() {
        exportLabel = null
        exportDone = false
        lastSaved = null
    }

    /** Non-null while an export runs or its result is shown (shown in a progress dialog). */
    var exportLabel by mutableStateOf<String?>(null)
        private set
    var exportProgress by mutableFloatStateOf(0f)
        private set
    private var exportJob: Job? = null

    private var sourceBitmap: Bitmap? = null
    private val undoStack = ArrayList<EditAction>()
    private val redoStack = ArrayList<EditAction>()

    // live stroke state
    private var liveLayer: Layer? = null
    private var livePoints = FloatArray(256)
    private var liveCount = 0
    private var erased = ArrayList<Pair<Int, Stroke>>()

    init {
        val layer = project.newLayer("Layer 1")
        project.layers.add(layer)
    }

    // ------------------------------------------------------------------ import / generate

    fun loadImage(uri: Uri) {
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.IO) { ImageLoader.load(app, uri, settings.maxSide) }
            if (bmp == null) {
                toast("Could not open that image")
                return@launch
            }
            resetProject()
            setSource(bmp)
            styleSheetRequest = true
        }
    }

    /** Loads an asset (default assets/demo.png) and generates + animates it like an imported photo. */
    fun runDemo(asset: String = "demo.png") {
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.IO) { ImageLoader.loadAsset(app, asset, settings.maxSide) }
            if (bmp == null) {
                toast("Could not open demo image")
                return@launch
            }
            resetProject()
            setSource(bmp)
            generate()
        }
    }

    /** Starts an empty project (no photo, one blank layer). */
    fun newProject() {
        resetProject()
        sourceBitmap = null
        sourceThumbnail = null
        hasProject = false
        styleSheetRequest = false
        project.background = null
        project.width = 1024
        project.height = 1024
    }

    /** Called from MainActivity with the launch intent extras. */
    fun handleIntentFlags(
        autodemo: Boolean,
        showLayers: Boolean,
        autoExport: Boolean,
        autoVideo: Boolean = false,
        demo: String? = null,
        mode: String? = null,
        engine: String? = null,
        shading: String? = null,
        testImage: String? = null,
    ) {
        if (autoVideo) pendingAutoVideo = true
        if (showLayers) pendingShowLayers = true
        if (autoExport) pendingAutoExport = true
        when (mode?.lowercase()) {
            "outline" -> settings = settings.copy(mode = DrawMode.OUTLINE)
            "shading" -> settings = settings.copy(mode = DrawMode.OUTLINE_SHADING)
            "color" -> settings = settings.copy(mode = DrawMode.COLOUR_FILL)
        }
        applyIntentOptions(engine, shading)
        if (autodemo) runDemo(
            when {
                testImage != null -> "testimages/$testImage.jpg"
                demo == "color" -> "demo_color.png"
                else -> "demo.png"
            }
        )
    }

    private fun resetProject() {
        project.layers.clear()
        project.layers.add(project.newLayer("Layer 1"))
        project.selectedIndex = 0
        clearHistory()
        anim.showFinished()
    }

    private fun setSource(bmp: Bitmap) {
        sourceBitmap = bmp
        sourceThumbnail = try {
            val m = max(bmp.width, bmp.height).coerceAtLeast(1)
            val k = 320f / m
            if (k >= 1f) bmp.asImageBitmap()
            else Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true).asImageBitmap()
        } catch (t: Throwable) {
            null
        }
        hasProject = true
        project.width = bmp.width
        project.height = bmp.height
        project.background = bmp
        project.backgroundVisible = true
        project.backgroundOpacity = 1f
    }

    fun generate() {
        val bmp = sourceBitmap
        if (bmp == null) {
            toast("Import a photo first")
            return
        }
        if (generating) return
        generating = true
        anim.showFinished()
        val genStart = android.os.SystemClock.elapsedRealtime()
        viewModelScope.launch {
            try {
                val ps = pipelineSettings
                val m = model()
                generationStage = "Starting"
                generationProgress = 0f
                val (drawing, scaledBg) = withContext(Dispatchers.Default) {
                    val w = bmp.width
                    val h = bmp.height
                    val pixels = IntArray(w * h)
                    bmp.getPixels(pixels, 0, w, 0, 0, w, h)
                    val d = SketchPipeline.run(pixels, w, h, ps, m) { stage, fraction ->
                        generationStage = stage
                        generationProgress = fraction
                    }
                    val bg = if (w != d.width || h != d.height) {
                        Bitmap.createScaledBitmap(bmp, d.width, d.height, true)
                    } else {
                        null
                    }
                    d to bg
                }
                lastAppliedSettings = ps
                applyDrawing(drawing, scaledBg)
                android.util.Log.i(
                    "SketcherGen",
                    "GEN_DONE ms=${android.os.SystemClock.elapsedRealtime() - genStart} " +
                        "outline=${drawing.outline.size} shading=${drawing.shading.size} fill=${drawing.fill.size} " +
                        "size=${drawing.width}x${drawing.height} settings=$ps",
                )
                generationCount++
                if (pendingAutoVideo) {
                    pendingAutoVideo = false
                    autoVideo()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                android.util.Log.e("SketcherGen", "GEN_FAIL", t)
                toast(
                    if (t is OutOfMemoryError) "Not enough memory - try a smaller image size in Settings"
                    else "Generation failed: ${t.message}",
                )
            } finally {
                generating = false
                generationStage = ""
                generationProgress = 0f
            }
        }
    }

    private var lastAppliedSettings = PipelineSettings()

    private fun applyDrawing(drawing: GeneratedDrawing, scaledBackground: Bitmap?) {
        project.width = drawing.width
        project.height = drawing.height
        if (scaledBackground != null) project.background = scaledBackground
        val outline = project.newLayer("Outline")
        outline.strokes.addAll(drawing.outline)
        val mine = project.newLayer("My Drawing")
        val ps = lastAppliedSettings
        val stack = ArrayList<Layer>()
        if (ps.colourFill) {
            // Bottom -> top: Colour Fill, Outline, (Shading), My Drawing. Outline is animated first.
            val fill = project.newLayer("Colour Fill")
            fill.strokes.addAll(drawing.fill)
            outline.animPriority = -1
            stack.add(fill)
        }
        stack.add(outline)
        if (ps.shading != ShadingLevel.NONE) {
            val shading = project.newLayer("Shading")
            shading.strokes.addAll(drawing.shading)
            stack.add(shading)
        }
        stack.add(mine)
        project.layers.clear()
        project.layers.addAll(stack)
        project.selectedIndex = stack.lastIndex
        project.backgroundVisible = false
        clearHistory()
        val extra = if (ps.colourFill) "${drawing.fill.size} fill" else "${drawing.shading.size} shading"
        toast("${drawing.outline.size} outline + $extra strokes")
        anim.restart()
    }

    // ------------------------------------------------------------------ layers

    fun selectLayer(layer: Layer) {
        val i = project.layers.indexOf(layer)
        if (i >= 0) project.selectedIndex = i
    }

    fun addLayer() {
        val layer = project.newLayer("Layer ${project.layers.size + 1}")
        project.layers.add(layer)
        project.selectedIndex = project.layers.lastIndex
    }

    fun deleteLayer(layer: Layer) {
        if (project.layers.size <= 1) {
            toast("Keep at least one layer")
            return
        }
        val selected = project.selectedLayer
        project.layers.remove(layer)
        val keep = if (selected === layer) project.layers.lastOrNull() else selected
        project.selectedIndex = max(0, project.layers.indexOf(keep))
        clearHistory()
    }

    /** delta +1 moves the layer up the stack (drawn later), -1 down. */
    fun moveLayer(layer: Layer, delta: Int) {
        val from = project.layers.indexOf(layer)
        val to = from + delta
        if (from < 0 || to < 0 || to >= project.layers.size) return
        val selected = project.selectedLayer
        project.layers.removeAt(from)
        project.layers.add(to, layer)
        project.selectedIndex = max(0, project.layers.indexOf(selected))
    }

    // ------------------------------------------------------------------ drawing

    /** Returns true when the touch starts a stroke/erase on the selected layer. */
    fun beginStroke(x: Float, y: Float): Boolean {
        val layer = project.selectedLayer ?: return false
        if (layer.locked || !layer.visible) return false
        if (anim.active) anim.showFinished()
        liveLayer = layer
        liveCount = 0
        erased = ArrayList()
        if (tool == Tool.BRUSH) addLivePoint(x, y) else eraseAt(x, y)
        liveTick++
        return true
    }

    fun extendStroke(x: Float, y: Float) {
        if (liveLayer == null) return
        if (tool == Tool.BRUSH) addLivePoint(x, y) else eraseAt(x, y)
        liveTick++
    }

    /** [cancel] discards a brush stroke; erasing that already happened is kept. */
    fun endStroke(cancel: Boolean) {
        val layer = liveLayer ?: return
        if (tool == Tool.BRUSH) {
            if (!cancel && liveCount > 0) {
                if (liveCount == 1) {
                    // Tap: add a second point directly (bypasses the dedupe) so a dot is drawn.
                    livePoints[2] = livePoints[0] + 0.01f
                    livePoints[3] = livePoints[1]
                    liveCount = 2
                }
                val stroke = Stroke(livePoints.copyOf(liveCount * 2), brushColor, brushSize, brushType, brushAlpha)
                layer.strokes.add(stroke)
                pushUndo(AddStrokeAction(layer, stroke))
            }
        } else if (erased.isNotEmpty()) {
            pushUndo(EraseAction(layer, erased))
        }
        liveLayer = null
        liveCount = 0
        erased = ArrayList()
        liveTick++
    }

    /** The brush stroke being drawn right now, if any. */
    fun liveStroke(): Stroke? {
        if (liveLayer == null || tool != Tool.BRUSH || liveCount == 0) return null
        val pts = if (liveCount == 1) {
            floatArrayOf(livePoints[0], livePoints[1], livePoints[0] + 0.01f, livePoints[1])
        } else {
            livePoints.copyOf(liveCount * 2)
        }
        return Stroke(pts, brushColor, brushSize, brushType, brushAlpha)
    }

    val liveLayerId: Long get() = liveLayer?.id ?: -1L

    private fun addLivePoint(x: Float, y: Float) {
        if (liveCount > 0) {
            val dx = x - livePoints[(liveCount - 1) * 2]
            val dy = y - livePoints[(liveCount - 1) * 2 + 1]
            if (dx * dx + dy * dy < 0.25f) return
        }
        if ((liveCount + 1) * 2 > livePoints.size) livePoints = livePoints.copyOf(livePoints.size * 2)
        livePoints[liveCount * 2] = x
        livePoints[liveCount * 2 + 1] = y
        liveCount++
    }

    private fun eraseAt(x: Float, y: Float) {
        val layer = liveLayer ?: return
        val radius = max(brushSize * 1.5f, 6f)
        var i = 0
        while (i < layer.strokes.size) {
            val s = layer.strokes[i]
            if (strokeNear(s, x, y, radius + s.width / 2f)) {
                erased.add(i to s)
                layer.strokes.removeAt(i)
            } else {
                i++
            }
        }
    }

    private fun strokeNear(s: Stroke, x: Float, y: Float, r: Float): Boolean {
        val p = s.points
        if (p.size < 2) return false
        if (segmentDistance(x, y, p[0], p[1], p[0], p[1]) <= r) return true
        var i = 2
        while (i < p.size) {
            if (segmentDistance(x, y, p[i - 2], p[i - 1], p[i], p[i + 1]) <= r) return true
            i += 2
        }
        return false
    }

    private fun segmentDistance(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        var t = if (len2 > 0f) ((px - ax) * dx + (py - ay) * dy) / len2 else 0f
        t = t.coerceIn(0f, 1f)
        val cx = ax + t * dx - px
        val cy = ay + t * dy - py
        return sqrt(cx * cx + cy * cy)
    }

    // ------------------------------------------------------------------ undo / redo

    private fun pushUndo(action: EditAction) {
        undoStack.add(action)
        redoStack.clear()
        syncHistoryFlags()
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        val a = undoStack.removeAt(undoStack.lastIndex)
        a.undo()
        redoStack.add(a)
        syncHistoryFlags()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        val a = redoStack.removeAt(redoStack.lastIndex)
        a.redo()
        undoStack.add(a)
        syncHistoryFlags()
    }

    private fun clearHistory() {
        undoStack.clear()
        redoStack.clear()
        syncHistoryFlags()
    }

    private fun syncHistoryFlags() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }

    // ------------------------------------------------------------------ export

    /** Copies layers and their strokes so background rendering can't race with edits. */
    private fun snapshotLayers(only: Layer?): List<Layer> =
        (if (only != null) listOf(only) else project.layers.toList()).map { src ->
            Layer(src.id, src.name).also {
                it.visible = src.visible
                it.opacity = src.opacity
                it.inAnimation = src.inAnimation
                it.strokes.addAll(src.strokes.toList())
            }
        }

    private fun mimeFor(path: String) = when (path.substringAfterLast('.').lowercase()) {
        "mp4" -> "video/mp4"
        "gif" -> "image/gif"
        "jpg", "jpeg" -> "image/jpeg"
        else -> "image/png"
    }

    /** Looks up the MediaStore uri of a file saved under Pictures/ or Movies/ (API 29+); null otherwise. */
    private fun resolveSavedUri(path: String, mime: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || path.startsWith("/")) return null
        return try {
            val collection = if (mime.startsWith("video")) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            app.contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(path.substringAfterLast('/'), path.substringBeforeLast('/') + "/"),
                null,
            )?.use { c -> if (c.moveToFirst()) ContentUris.withAppendedId(collection, c.getLong(0)) else null }
        } catch (t: Throwable) {
            null
        }
    }

    private fun finishExport(saved: SavedExport) {
        lastSaved = saved
        exportProgress = 1f
        exportDone = true
    }

    private fun exportSnapshot(layers: List<Layer>, w: Int, h: Int, scale: Int, fmt: ExportFormat, transparent: Boolean, ignoreState: Boolean, name: String) {
        if (exportJob?.isActive == true) return
        exportDone = false
        lastSaved = null
        exportProgress = 0f
        exportLabel = "Saving image..."
        exportJob = viewModelScope.launch {
            var ok = false
            try {
                val saved = withContext(Dispatchers.Default) {
                    val bmp = Exporter.render(w, h, layers, scale, transparent, ignoreState)
                    val where = try {
                        Exporter.save(app, bmp, fmt, name)
                    } finally {
                        bmp.recycle()
                    }
                    where?.let { SavedExport(it, resolveSavedUri(it, fmt.mime), fmt.mime) }
                }
                if (saved != null) {
                    finishExport(saved)
                    ok = true
                } else {
                    toast("Export failed")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                toast("Export failed")
            } finally {
                if (!ok) exportLabel = null
            }
        }
    }

    /** One file per visible layer (transparent PNG each, ignoring opacity), saved in sequence. */
    fun exportLayersSeparately(scale: Int, format: ExportFormat, transparent: Boolean) {
        if (exportJob?.isActive == true) return
        val layers = project.layers.filter { it.visible && it.strokes.isNotEmpty() }
        if (layers.isEmpty()) {
            toast("Nothing to export yet")
            return
        }
        val snaps = layers.map { snapshotLayers(it).first() }
        val w = project.width
        val h = project.height
        exportDone = false
        lastSaved = null
        exportProgress = 0f
        exportLabel = "Saving layers..."
        exportJob = viewModelScope.launch {
            var ok = false
            try {
                val results = withContext(Dispatchers.Default) {
                    val out = ArrayList<SavedExport>()
                    for ((i, l) in snaps.withIndex()) {
                        ensureActive()
                        val bmp = Exporter.render(w, h, listOf(l), scale, true, true)
                        val where = try {
                            Exporter.save(app, bmp, ExportFormat.PNG, l.name)
                        } finally {
                            bmp.recycle()
                        }
                        if (where != null) out.add(SavedExport(where, null, ExportFormat.PNG.mime))
                        exportProgress = (i + 1f) / snaps.size
                    }
                    out
                }
                if (results.isNotEmpty()) {
                    val dir = results.last().path.substringBeforeLast('/')
                    finishExport(SavedExport("${results.size} layers in $dir", null, ExportFormat.PNG.mime))
                    ok = true
                } else {
                    toast("Export failed")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                toast("Export failed")
            } finally {
                if (!ok) exportLabel = null
            }
        }
    }

    fun export(scale: Int, format: ExportFormat, transparent: Boolean, selectedLayerOnly: Boolean) {
        val layer = if (selectedLayerOnly) project.selectedLayer else null
        if (selectedLayerOnly && layer == null) return
        val isLayer = layer != null
        val fmt = if (isLayer) ExportFormat.PNG else format
        exportSnapshot(
            snapshotLayers(layer), project.width, project.height, scale, fmt,
            transparent = isLayer || (fmt == ExportFormat.PNG && transparent),
            ignoreState = isLayer, name = layer?.name ?: "drawing",
        )
    }

    /** Exports all visible layers as PNG, then the Outline layer alone as transparent PNG. */
    fun autoExport() {
        val outline = project.layers.firstOrNull { it.name == "Outline" }
        export(1, ExportFormat.PNG, false, false)
        if (outline != null) {
            val layers = snapshotLayers(outline)
            val w = project.width
            val h = project.height
            viewModelScope.launch {
                exportJob?.join()
                exportSnapshot(layers, w, h, 1, ExportFormat.PNG, true, true, "Outline")
            }
        }
    }

    /** Exports the draw-along animation as MP4 or GIF on a background thread, with progress + cancel. */
    fun exportAnimation(kind: AnimExportKind) = startAnimationExport(listOf(kind))

    /** For CI: after generation, exports both MP4 and GIF. */
    fun autoVideo() = startAnimationExport(listOf(AnimExportKind.VIDEO, AnimExportKind.GIF))

    fun cancelExport() {
        exportJob?.cancel()
    }

    private fun startAnimationExport(kinds: List<AnimExportKind>) {
        if (exportJob?.isActive == true) return
        if (project.layers.all { it.strokes.isEmpty() }) {
            toast("Nothing to export yet")
            return
        }
        val layers = snapshotLayers(null)
        val w = project.width
        val h = project.height
        val duration = anim.durationSec
        exportProgress = 0f
        exportLabel = "Preparing..."
        exportDone = false
        lastSaved = null
        exportJob = viewModelScope.launch {
            var animOk = false
            try {
                val saved = withContext(Dispatchers.Default) {
                    val results = ArrayList<String>()
                    for ((i, kind) in kinds.withIndex()) {
                        exportLabel = if (kind == AnimExportKind.VIDEO) "Exporting MP4 video..." else "Exporting GIF..."
                        val onProgress = { p: Float ->
                            val overall = (i + p) / kinds.size
                            if (overall - exportProgress >= 0.005f || overall >= 1f) exportProgress = overall
                        }
                        val check = { ensureActive() }
                        val where = if (kind == AnimExportKind.VIDEO) {
                            VideoExporter.export(app, w, h, layers, duration, "animation", onProgress, check)
                        } else {
                            GifExporter.export(app, w, h, layers, duration, "animation", onProgress, check)
                        }
                        if (where != null) results.add(where)
                    }
                    results
                }
                if (saved.isNotEmpty()) {
                    val last = saved.last()
                    val mime = mimeFor(last)
                    finishExport(SavedExport(saved.joinToString("\n"), resolveSavedUri(last, mime), mime))
                    animOk = true
                } else {
                    toast("Export failed")
                }
            } catch (e: CancellationException) {
                toast("Export cancelled")
                throw e
            } catch (t: Throwable) {
                toast("Export failed: ${t.message}")
            } finally {
                if (!animOk) exportLabel = null
            }
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(app, msg, Toast.LENGTH_LONG).show()
    }
}
