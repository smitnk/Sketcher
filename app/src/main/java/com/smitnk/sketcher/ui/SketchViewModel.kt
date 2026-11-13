package com.smitnk.sketcher.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smitnk.sketcher.anim.AnimationController
import com.smitnk.sketcher.export.ExportFormat
import com.smitnk.sketcher.export.Exporter
import com.smitnk.sketcher.image.GeneratedDrawing
import com.smitnk.sketcher.image.GeneratorSettings
import com.smitnk.sketcher.image.ImageLoader
import com.smitnk.sketcher.image.Stroke
import com.smitnk.sketcher.image.StrokeGenerator
import com.smitnk.sketcher.model.Layer
import com.smitnk.sketcher.model.Project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.sqrt

enum class Tool { BRUSH, ERASER }

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

    var settings by mutableStateOf(GeneratorSettings())
    var generating by mutableStateOf(false)
        private set

    var tool by mutableStateOf(Tool.BRUSH)
    var brushColor by mutableIntStateOf(0xFF000000.toInt())
    var brushSize by mutableFloatStateOf(4f)

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
            toast("Photo loaded - tap Generate")
        }
    }

    /** Loads assets/demo.png and generates + animates it like an imported photo. */
    fun runDemo() {
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.IO) { ImageLoader.loadAsset(app, "demo.png", settings.maxSide) }
            if (bmp == null) {
                toast("Could not open demo image")
                return@launch
            }
            setSource(bmp)
            generate()
        }
    }

    /** Called from MainActivity with the launch intent extras. */
    fun handleIntentFlags(autodemo: Boolean, showLayers: Boolean, autoExport: Boolean) {
        if (showLayers) pendingShowLayers = true
        if (autoExport) pendingAutoExport = true
        if (autodemo) runDemo()
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
        viewModelScope.launch {
            try {
                val (drawing, scaledBg) = withContext(Dispatchers.Default) {
                    val w = bmp.width
                    val h = bmp.height
                    val pixels = IntArray(w * h)
                    bmp.getPixels(pixels, 0, w, 0, 0, w, h)
                    val d = StrokeGenerator.generate(pixels, w, h, settings)
                    val bg = if (w != d.width || h != d.height) {
                        Bitmap.createScaledBitmap(bmp, d.width, d.height, true)
                    } else {
                        null
                    }
                    d to bg
                }
                applyDrawing(drawing, scaledBg)
                generationCount++
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                toast(
                    if (t is OutOfMemoryError) "Not enough memory - try a smaller image size in Settings"
                    else "Generation failed: ${t.message}",
                )
            } finally {
                generating = false
            }
        }
    }

    private fun applyDrawing(drawing: GeneratedDrawing, scaledBackground: Bitmap?) {
        project.width = drawing.width
        project.height = drawing.height
        if (scaledBackground != null) project.background = scaledBackground
        val outline = project.newLayer("Outline")
        outline.strokes.addAll(drawing.outline)
        val shading = project.newLayer("Shading")
        shading.strokes.addAll(drawing.shading)
        val mine = project.newLayer("My Drawing")
        project.layers.clear()
        project.layers.addAll(listOf(outline, shading, mine))
        project.selectedIndex = 2
        project.backgroundVisible = false
        clearHistory()
        toast("${drawing.outline.size} outline + ${drawing.shading.size} shading strokes")
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
                val stroke = Stroke(livePoints.copyOf(liveCount * 2), brushColor, brushSize)
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
        return Stroke(pts, brushColor, brushSize)
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
                it.strokes.addAll(src.strokes.toList())
            }
        }

    private fun exportSnapshot(layers: List<Layer>, w: Int, h: Int, scale: Int, fmt: ExportFormat, transparent: Boolean, ignoreState: Boolean, name: String) {
        viewModelScope.launch {
            val where = try {
                withContext(Dispatchers.Default) {
                    val bmp = Exporter.render(w, h, layers, scale, transparent, ignoreState)
                    try {
                        Exporter.save(app, bmp, fmt, name)
                    } finally {
                        bmp.recycle()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                null
            }
            toast(if (where != null) "Saved: $where" else "Export failed")
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
            exportSnapshot(snapshotLayers(outline), project.width, project.height, 1, ExportFormat.PNG, true, true, "Outline")
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(app, msg, Toast.LENGTH_LONG).show()
    }
}
