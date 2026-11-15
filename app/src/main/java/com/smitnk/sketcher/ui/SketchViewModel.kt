package com.smitnk.sketcher.ui

import android.app.Application
import android.graphics.Bitmap
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smitnk.sketcher.anim.AnimationController
import com.smitnk.sketcher.data.ProjectMeta
import com.smitnk.sketcher.data.ProjectRepository
import com.smitnk.sketcher.data.applySnapshot
import com.smitnk.sketcher.data.toSnapshot
import com.smitnk.sketcher.export.AnimExportKind
import com.smitnk.sketcher.export.ExportFormat
import com.smitnk.sketcher.export.ExportKind
import com.smitnk.sketcher.export.ExportResult
import com.smitnk.sketcher.export.ExportState
import com.smitnk.sketcher.export.blockedReason
import com.smitnk.sketcher.export.summary
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
import com.smitnk.sketcher.image.models.ModelManager
import com.smitnk.sketcher.image.models.ModelSpec
import com.smitnk.sketcher.image.models.ModelSpecs
import com.smitnk.sketcher.image.pipeline.Accel
import com.smitnk.sketcher.image.pipeline.LineArtModel
import com.smitnk.sketcher.image.pipeline.MlLog
import com.smitnk.sketcher.image.pipeline.OnnxLineArtModel
import com.smitnk.sketcher.image.pipeline.PipelineSettings
import com.smitnk.sketcher.image.pipeline.ShadingLevel
import com.smitnk.sketcher.image.pipeline.SketchPipeline
import com.smitnk.sketcher.model.Layer
import com.smitnk.sketcher.model.LayerKind
import com.smitnk.sketcher.model.Project
import com.smitnk.sketcher.ui.canvas.StrokeSession
import com.smitnk.sketcher.ui.generate.LineModelUi
import com.smitnk.sketcher.ui.home.RecentProjectUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import com.smitnk.sketcher.image.pipeline.GenerationAbort
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.smitnk.sketcher.data.ProjectLoad
import com.smitnk.sketcher.data.ProjectSession
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InterruptedIOException
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.sqrt

enum class Tool { BRUSH, ERASER }

/** Result of the last successful export. [uri] is null when it cannot be shared (legacy storage / several files). */
class SavedExport(val path: String, val uri: Uri?, val mime: String)

private interface EditAction {
    fun undo()
    fun redo()

    /** Approximate retained bitmap bytes (for the history memory cap). */
    val cost: Long get() = 0L
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

/** Eraser stroke on a pencil-reveal layer: swaps full copies of the ink texture (simple, exact). */
private class InkEraseAction(val layer: Layer, val before: Bitmap, val after: Bitmap) : EditAction {
    private fun copy(b: Bitmap) = b.copy(Bitmap.Config.ARGB_8888, true)
    override fun undo() { layer.inkTexture = copy(before) }
    override fun redo() { layer.inkTexture = copy(after) }
    override val cost: Long get() = (before.allocationByteCount + after.allocationByteCount).toLong()
}

private const val MAX_UNDO = 50
private const val MAX_HISTORY_BYTES = 64L * 1024 * 1024
private const val DEFAULT_PROJECT_NAME = "Untitled sketch"
private const val AUTOSAVE_DELAY_MS = 2000L
private const val SAVE_RETRY_MS = 10_000L
private const val KEY_MODEL_ID = "model_id"
private const val KEY_ACCEL_CHOICE = "accel_choice"
private const val KEY_NNAPI_ACK = "nnapi_warning_ack"
private const val PREV_JOIN_MS = 6_000L
private const val ENGINE_RELEASE_WAIT_MS = 3_000L

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

    // Single source of truth for a generation run (see GenerationState.kt); late callbacks of older runs are dropped.
    private val genMachine = GenerationMachine()
    var generationState by mutableStateOf<GenerationState>(GenerationState.Idle)
        private set

    private fun publishGen() { generationState = genMachine.state.value }

    val generationStage: String get() = (generationState as? GenerationState.Running)?.stage ?: ""
    val generationProgress: Float get() = (generationState as? GenerationState.Running)?.progress ?: 0f

    /** True while a generation runs. */
    val generating: Boolean get() = generationState is GenerationState.Running

    /** Generation and the speed test exclude each other; the UI disables the other action while one runs. */
    val canGenerate: Boolean get() = !benchmarking && !generating
    val canBenchmark: Boolean get() = !benchmarking && !generating

    /** Seconds since the current generation stage began (ticks while generating). */
    private var stageElapsedSec by mutableIntStateOf(0)
    private var stageStartMs = 0L
    private var genStartMs = 0L

    private val aiStage get() = generationStage.startsWith("Running AI")

    /** Stage text for the overlay: while the AI model runs, show its name and elapsed seconds instead of a frozen %. */
    val generationStageLabel: String
        get() = if (aiStage) "AI (${aiModelName()}) running\u2026 ${stageElapsedSec}s" else generationStage

    /** Null (indeterminate) while the AI model runs: its progress is unknowable. */
    val generationDisplayProgress: Float? get() = if (aiStage) null else generationProgress

    private fun aiModelName(): String =
        (lineArtModel?.specName ?: (ModelSpecs.byId(selectedLineModelId) ?: ModelSpecs.INFORMATIVE).name)

    /** Progress callback of run [id]; a superseded/reset run is stopped instead of overwriting the new run's state. */
    private fun onStage(id: Long, stage: String, fraction: Float) {
        val prev = generationStage
        if (!genMachine.stage(id, stage, fraction)) throw CancellationException("generation superseded")
        if (stage != prev) {
            val now = android.os.SystemClock.elapsedRealtime()
            stageStartMs = now
            stageElapsedSec = 0
            MlLog.i("stage \"$stage\" at ${now - genStartMs} ms (progress ${(fraction * 100).toInt()}%)")
        }
        publishGen()
    }

    // Per-run built-in fallback model + why it was needed (never touches the user's selected model).
    private var runFallback: OnnxLineArtModel? = null
    private var runFellBackTo: String? = null
    private var runFallbackReason: String? = null

    /**
     * Wraps the live model. A genuine model failure (session failure, timeout, OOM-ish error) finishes THIS run with
     * the bundled informative model and a toast; the user's selected model is left alone. "Engine busy" is not a
     * model failure: it is waited out and, if it persists, the run is aborted without any fallback.
     */
    private fun withFallback(primary: OnnxLineArtModel, run: Long): LineArtModel = LineArtModel { argb, w, h ->
        inferWithPolicy(
            primary = { primary.infer(argb, w, h) },
            fallback = { t ->
                if (primary.specId == ModelSpecs.DEFAULT_ID) throw t
                val why = (t.message ?: t.javaClass.simpleName).take(120)
                MlLog.w("model ${primary.specId} failed, falling back to informative for this run", t)
                viewModelScope.launch(Dispatchers.Main) {
                    toast("${primary.specName} model failed ($why). Using the built-in model for this drawing.")
                }
                val fb = runFallback ?: OnnxLineArtModel(app, ModelSpecs.INFORMATIVE, null, longSide = primary.longSide)
                    .also { runFallback = it }
                runFellBackTo = ModelSpecs.DEFAULT_ID
                runFallbackReason = why
                inferWithPolicy(
                    primary = { fb.infer(argb, w, h) },
                    fallback = { e -> throw e },
                    isCancelled = { !genMachine.isCurrent(run) },
                )
            },
            isCancelled = { !genMachine.isCurrent(run) },
        )
    }

    /** Asks every engine instance of the current run to stop (ORT run terminate); safe from any thread. */
    private fun cancelEngineRuns() {
        lineArtModel?.cancelRun()
        runFallback?.cancelRun()
    }

    // ------------------------------------------------------------------ line-art models / processing

    private val models = ModelManager(app)
    private val mlPrefs = app.getSharedPreferences(OnnxLineArtModel.PREFS, Context.MODE_PRIVATE).also {
        OnnxLineArtModel.migratePrefs(app) // v2: drop auto-selected NNAPI choices
    }
    private var lineArtModel: OnnxLineArtModel? = null

    /** Id of the line model in use (persisted). Falls back to the bundled model when the file is missing. */
    var selectedLineModelId by mutableStateOf(
        mlPrefs.getString(KEY_MODEL_ID, null)?.takeIf { ModelSpecs.byId(it) != null } ?: ModelSpecs.DEFAULT_ID,
    )
        private set

    // Per-model install state, updated atomically. Bundled models are Installed at once; downloaded ones are
    // scanned on IO after start. modelStateUi mirrors the flow for Compose.
    private val modelStates = MutableStateFlow<Map<String, ModelState>>(
        models.list().associate { it.id to (if (it.isBundled) ModelState.Installed else ModelState.NotInstalled) },
    )
    private var modelStateUi by mutableStateOf(modelStates.value)
    private val installedModelIds: Set<String>
        get() = modelStates.value.filterValues { it == ModelState.Installed }.keys
    private val downloadJobs = HashMap<String, Job>()

    // Applied once no generation/benchmark is running (never hot-swap a model mid-run).
    private var pendingSelectId: String? = null
    private val retiredModels = ArrayList<OnnxLineArtModel>()

    init {
        viewModelScope.launch { modelStates.collect { modelStateUi = it } }
    }

    /** Rows for the style picker's model cards. */
    val lineModels: List<LineModelUi>
        get() = models.list().map { s ->
            LineModelUi(
                id = s.id,
                name = s.name,
                subtitle = when (s.id) {
                    "informative" -> "Built-in, balanced"
                    "realistic" -> "ControlNet lineart, detailed"
                    "coarse" -> "Bold simple lines, great for poses"
                    "anime" -> "Best for 3D/anime characters; large download"
                    else -> s.description
                },
                sizeLabel = when {
                    s.isBundled -> "Built-in"
                    s.sizeBytes != null -> "${(s.sizeBytes / (1024 * 1024)).coerceAtLeast(1)} MB"
                    s.id == "anime" -> "Large download"
                    else -> "Download"
                },
                installed = modelStateUi[s.id] == ModelState.Installed,
                downloading = (modelStateUi[s.id] as? ModelState.Downloading)?.progress,
                recommended = s.id == ModelSpecs.DEFAULT_ID,
            )
        }

    var showMlSettings by mutableStateOf(false)
        private set

    fun openMlSettings() {
        refreshMlStatus()
        showMlSettings = true
    }
    fun closeMlSettings() { showMlSettings = false }

    private fun refreshInstalledModels() {
        viewModelScope.launch(Dispatchers.IO) {
            val all = models.list().map { it.id }
            val ids = models.list().filter { models.isAvailable(it.id) }.map { it.id }.toSet()
            withContext(Dispatchers.Main) {
                modelStates.update { ModelStates.scanned(it, ids, all) }
                // The persisted pick may have been deleted externally: never leave a missing model selected.
                if (selectedLineModelId !in ids) selectLineModelNow(ModelSpecs.DEFAULT_ID)
            }
        }
    }

    private fun closeInBackground(m: OnnxLineArtModel?) {
        // close() is terminal and bounded (waits <= 10 s, then trips the breaker); never block the main thread on it.
        if (m != null) Thread({ try { m.close() } catch (e: Throwable) { android.util.Log.w("SketcherModel", "close failed", e) } }, "model-close").start()
    }

    private fun buildModel(): OnnxLineArtModel {
        val spec = ModelSpecs.byId(selectedLineModelId) ?: ModelSpecs.INFORMATIVE
        val useSpec = if (models.isAvailable(spec.id)) spec else ModelSpecs.INFORMATIVE
        return OnnxLineArtModel(app, useSpec, models.file(useSpec.id), longSide = OnnxLineArtModel.DEFAULT_LONG_SIDE)
    }

    private fun model(): OnnxLineArtModel {
        val cur = lineArtModel
        if (cur != null && !cur.isClosed) return cur
        return buildModel().also { lineArtModel = it }
    }

    /** True while a run owns the engine; models must not be swapped/closed then. */
    private val engineBusy: Boolean get() = generating || benchmarking

    /** Closes models retired during a run and applies a selection deferred by a finished download. */
    private fun flushDeferred() {
        if (engineBusy) return
        if (retiredModels.isNotEmpty()) {
            val l = retiredModels.toList()
            retiredModels.clear()
            l.forEach { closeInBackground(it) }
        }
        pendingSelectId?.let { id -> pendingSelectId = null; selectLineModelNow(id) }
    }

    /** Switches the line model (only if installed). A model in use by a running generation is closed after it. */
    fun selectLineModel(id: String) = selectLineModelNow(id)

    private fun selectLineModelNow(id: String) {
        if (id == selectedLineModelId || modelStates.value[id] != ModelState.Installed) return
        mlPrefs.edit().putString(KEY_MODEL_ID, id).apply()
        selectedLineModelId = id
        val old = lineArtModel
        lineArtModel = null
        if (old != null) { if (engineBusy) retiredModels.add(old) else closeInBackground(old) }
    }

    fun downloadLineModel(id: String) {
        val spec = models.spec(id) ?: return
        val cur = modelStates.value[id]
        if (spec.isBundled || cur == ModelState.Installed || cur is ModelState.Downloading || downloadJobs.containsKey(id)) return
        modelStates.update { ModelStates.beginDownload(it, id) }
        downloadJobs[id] = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val closer = AtomicReference<(() -> Unit)?>(null)
                    val finished = AtomicBoolean(false)
                    // A blocked socket read ignores shouldCancel: close the connection on cancellation instead.
                    val watcher = launch {
                        try { awaitCancellation() } finally { if (!finished.get()) closer.get()?.invoke() }
                    }
                    try {
                        models.download(
                            id,
                            onProgress = { done, total ->
                                val f = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
                                modelStates.update { ModelStates.progress(it, id, f) }
                            },
                            shouldCancel = { !isActive },
                            onOpen = { closer.set(it) },
                        )
                    } finally {
                        finished.set(true)
                        watcher.cancel()
                    }
                }
                modelStates.update { ModelStates.installed(it, id) }
                // Never swap models under a running generation: apply afterwards.
                if (engineBusy) pendingSelectId = id else selectLineModelNow(id)
                toast("${spec.name} model ready")
            } catch (e: CancellationException) {
                throw e
            } catch (e: InterruptedIOException) {
                // cancelled
            } catch (e: IOException) {
                modelStates.update { ModelStates.failed(it, id, e.message ?: "download failed") }
                toast("Download failed: ${e.message}")
            } catch (t: Throwable) {
                modelStates.update { ModelStates.failed(it, id, t.message ?: "download failed") }
                toast("Download failed: ${t.message}")
            } finally {
                modelStates.update { ModelStates.cancelled(it, id) } // no-op unless still "Downloading"
                downloadJobs.remove(id)
            }
        }
    }

    fun cancelDownload(id: String) {
        downloadJobs[id]?.cancel()
    }

    // Accelerator choice: "auto" | "cpu" | "xnnpack" | "nnapi_gpu" | "nnapi_npu".
    var selectedAccel by mutableStateOf(mlPrefs.getString(KEY_ACCEL_CHOICE, null) ?: "xnnpack")
        private set

    var benchmarking by mutableStateOf(false)
        private set

    /** id -> ms; null = provider failed; missing key = not run. Seeded from the last saved benchmark. */
    var benchmarkResults by mutableStateOf<Map<String, Long?>>(
        Accel.values().mapNotNull { a ->
            OnnxLineArtModel.savedBenchmarkMs(app, a)?.let { accelId(a) to it as Long? }
        }.toMap(),
    )
        private set

    var lastInferenceMs by mutableStateOf<Long?>(null)
        private set
    var activeAccelLabel by mutableStateOf<String?>(null)
        private set
    var fallbackReason by mutableStateOf<String?>(null)
        private set

    val accelOptions: List<Pair<String, String>> = listOf(
        "auto" to "Auto (fastest CPU)",
        "cpu" to "CPU",
        "xnnpack" to "CPU + XNNPACK",
        "nnapi_gpu" to "GPU (NNAPI)",
        "nnapi_npu" to "NPU (NNAPI)",
    )

    private fun accelId(a: Accel) = when (a) {
        Accel.CPU -> "cpu"
        Accel.XNNPACK -> "xnnpack"
        Accel.NNAPI_GPU -> "nnapi_gpu"
        Accel.NNAPI_NPU -> "nnapi_npu"
    }

    private fun accelLabel(a: Accel) = accelOptions.first { it.first == accelId(a) }.second

    private fun isNnapiId(id: String) = id.startsWith("nnapi")

    /** Concrete provider for a choice id; "auto" = fastest saved CPU/XNNPACK benchmark (never NNAPI), else XNNPACK. */
    private fun resolveAccel(id: String): Accel =
        Accel.values().firstOrNull { accelId(it) == id }
            ?: listOf(Accel.CPU, Accel.XNNPACK)
                .mapNotNull { a -> OnnxLineArtModel.savedBenchmarkMs(app, a)?.let { a to it } }
                .minByOrNull { it.second }?.first
            ?: Accel.XNNPACK

    /** NNAPI is opt-in: the first pick shows a warning dialog; only "Use anyway" applies it. */
    var nnapiWarningPending by mutableStateOf<String?>(null)
        private set

    fun selectAccel(id: String) {
        if (accelOptions.none { it.first == id }) return
        if (isNnapiId(id) && !mlPrefs.getBoolean(KEY_NNAPI_ACK, false)) { nnapiWarningPending = id; return }
        selectedAccel = id
        mlPrefs.edit().putString(KEY_ACCEL_CHOICE, id).apply()
        applyAccelInBackground()
    }

    fun confirmNnapiWarning() {
        val id = nnapiWarningPending ?: return
        nnapiWarningPending = null
        mlPrefs.edit().putBoolean(KEY_NNAPI_ACK, true).apply()
        selectAccel(id)
    }

    fun dismissNnapiWarning() { nnapiWarningPending = null }

    /** Applies the chosen accelerator to the live model. The setter waits for a running inference, so use IO. */
    private fun applyAccelInBackground() {
        val a = resolveAccel(selectedAccel)
        val m = lineArtModel
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (m != null) m.selectAccel(a) else mlPrefs.edit().putString("accel", a.name).apply()
            } catch (t: Throwable) {
                android.util.Log.w("SketcherModel", "accel change failed", t)
            }
        }
    }

    /** Pulls lastInferenceMs / activeAccel / fallbackReason from the live model into UI state. */
    private fun refreshMlStatus() {
        val m = lineArtModel ?: return
        lastInferenceMs = m.lastInferenceMs
        activeAccelLabel = m.activeAccel?.let { accelLabel(it) }
        fallbackReason = m.lastFallbackReason
    }

    /**
     * Times CPU and CPU+XNNPACK (NNAPI only if the user accepted the warning) on the bundled model. Runs only when
     * the user taps "Test speed"; never at launch. Mutually exclusive with generation (and, inside
     * OnnxLineArtModel, with every other session via the global lock).
     */
    fun runBenchmark() {
        if (benchmarking) return
        if (generating) { toast("Wait for the current drawing to finish"); return }
        benchmarking = true
        val withNnapi = mlPrefs.getBoolean(KEY_NNAPI_ACK, false) && isNnapiId(selectedAccel)
        benchJob = viewModelScope.launch {
            try {
                val res = withContext(Dispatchers.Default) {
                    val m = OnnxLineArtModel(app, ModelSpecs.INFORMATIVE, null)
                    val token = m.beginRun()
                    val watcher = launch { try { awaitCancellation() } finally { m.cancelRun(token) } }
                    try { m.benchmark(includeNnapi = withNnapi) } finally { watcher.cancel(); m.close() }
                }
                benchmarkResults = Accel.values().filter { it in res }.associate { accelId(it) to res[it] }
                if (selectedAccel == "auto") applyAccelInBackground()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                toast("Speed test failed: ${t.message}")
            } finally {
                benchmarking = false
                flushDeferred()
            }
        }
    }

    private var benchJob: Job? = null

    override fun onCleared() {
        // Final save: snapshot now (main thread); the session's own scope outlives viewModelScope and drains its queue.
        debounceJob?.cancel()
        submitSave("cleared")
        session.close()
        generateJob?.cancel()
        benchJob?.cancel()
        cancelEngineRuns()
        val m = lineArtModel
        lineArtModel = null
        closeInBackground(m)
        closeInBackground(runFallback)
        runFallback = null
        retiredModels.forEach { closeInBackground(it) }
        retiredModels.clear()
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
        val o = project.layers.indexOfFirst { it.kind == LayerKind.AUTO_OUTLINE }
        val at = if (o >= 0) o + 1 else (project.selectedIndex + 1).coerceIn(0, project.layers.size)
        project.layers.add(at, layer)
        project.selectedIndex = at
        markDirty()
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

    /**
     * One-shot UI events (open style sheet, CI auto-actions). Each carries the project session it belongs to;
     * the channel is drained on every project reset so a stale event can never fire on a later project.
     */
    private val editorEventChannel = Channel<EditorEvent>(Channel.BUFFERED)
    val editorEvents: Flow<EditorEvent> = editorEventChannel.receiveAsFlow()

    /** Id of the current project session (bumped on every reset / project switch). */
    val sessionId: Int get() = projectGen

    /** CI actions armed for the NEXT project (taken synchronously by runDemo) or the current one. */
    private var armedActions: Set<EditorAutoAction> = emptySet()

    /** CI actions waiting for generation to finish in the current session; cleared by [resetProject]. */
    private var sessionActions: Set<EditorAutoAction> = emptySet()

    private fun emitEditorEvent(e: EditorEvent) { editorEventChannel.trySend(e) }

    private fun drainEditorEvents() {
        while (editorEventChannel.tryReceive().isSuccess) { /* drop */ }
    }

    private fun fireSessionActions() {
        val acts = sessionActions
        sessionActions = emptySet()
        val sid = projectGen
        if (EditorAutoAction.SHOW_LAYERS in acts) emitEditorEvent(EditorEvent.ShowLayers(sid))
        if (EditorAutoAction.AUTO_EXPORT in acts) emitEditorEvent(EditorEvent.AutoExport(sid))
        if (EditorAutoAction.AUTO_VIDEO in acts) emitEditorEvent(EditorEvent.AutoVideo(sid))
    }

    /**
     * Automation intents (CI) must not wipe a project the user is editing: accepted only when nothing
     * unsaved is open, or when the intent carries a debug [label].
     */
    fun acceptsAutomation(label: String?): Boolean = label != null || !(hasProject && dirty)

    /** Small preview of the imported photo for the style sheet; computed once per source. */
    var sourceThumbnail by mutableStateOf<ImageBitmap?>(null)
        private set

    /** Export lifecycle (see [ExportState]). Only Running (or [generating]) blocks a new export. */
    var exportState by mutableStateOf<ExportState>(ExportState.Idle)
        private set

    /** Non-null = exporting is currently not possible, with the reason to show in the UI. */
    val exportBlockedReason: String?
        get() = exportState.blockedReason(generating)

    /** Result text/uri for the result dialog: one honest line per file; uri only of the last successful file. */
    val lastSaved: SavedExport?
        get() = when (val st = exportState) {
            is ExportState.Done -> {
                val ok = st.results.lastOrNull { it.ok }
                val mime = when (ok?.kind) {
                    ExportKind.VIDEO -> "video/mp4"
                    ExportKind.GIF -> "image/gif"
                    else -> mimeFor(ok?.path ?: "x.png")
                }
                SavedExport(st.summary(), ok?.uri?.let { Uri.parse(it) }, mime)
            }
            is ExportState.Failed -> SavedExport(st.reason, null, "image/*")
            else -> null
        }

    val exportDone: Boolean
        get() = exportState.let { it is ExportState.Done || it is ExportState.Failed }

    /** Dialog title: honest about partial failure. */
    val exportTitle: String?
        get() = when (val st = exportState) {
            is ExportState.Done -> if (st.allOk) null else if (st.anyOk) "Export partly failed" else "Export failed"
            is ExportState.Failed -> "Export failed"
            else -> null
        }

    /** Non-null while an export runs or its result is shown (shown in a progress dialog). */
    val exportLabel: String?
        get() = when (val st = exportState) {
            ExportState.Idle -> null
            is ExportState.Running -> st.label
            is ExportState.Done -> "Export finished"
            is ExportState.Failed -> st.reason
        }

    val exportProgress: Float
        get() = (exportState as? ExportState.Running)?.progress ?: if (exportDone) 1f else 0f

    /** Closes the export result dialog (never interrupts a running export). */
    fun dismissExport() {
        if (exportState !is ExportState.Running) exportState = ExportState.Idle
    }

    private var exportJob: Job? = null

    private var sourceBitmap: Bitmap? = null
    private val undoStack = ArrayList<EditAction>()
    private val redoStack = ArrayList<EditAction>()

    // live stroke state (frozen per gesture in the session)
    private var liveSession: StrokeSession? = null

    /** Bumped whenever a project is opened/reset so the canvas resets zoom and pan. */
    var projectEpoch by mutableIntStateOf(0)
        private set

    init {
        val layer = project.newLayer("Layer 1")
        project.layers.add(layer)
    }

    // ------------------------------------------------------------------ projects / autosave

    private val repo = ProjectRepository(app)

    /** Single writer for the open project (id allocation, ordering, retry, tombstones); see ProjectSession. */
    private val session = ProjectSession(repo, CoroutineScope(SupervisorJob() + Dispatchers.IO), DEFAULT_PROJECT_NAME)

    val currentProjectId: String? get() = session.projectId

    var projectName by mutableStateOf(DEFAULT_PROJECT_NAME)
        private set

    var recentProjects by mutableStateOf<List<RecentProjectUi>>(emptyList())
        private set

    /** Empty / Loading / Ready / Failed: the editor must show a loading indicator and block input while Loading. */
    var projectLoad by mutableStateOf(ProjectLoad.Empty)
        private set

    /** Non-null while autosave keeps failing (retried in the background every few seconds). */
    var saveError by mutableStateOf<String?>(null)
        private set

    init {
        viewModelScope.launch { session.load.collect { projectLoad = it } }
        viewModelScope.launch {
            session.saveError.collectLatest { e ->
                saveError = e?.let { it.message ?: it.javaClass.simpleName }
                if (e != null) {
                    delay(SAVE_RETRY_MS)
                    session.flush()
                }
            }
        }
    }

    private var dirty = false
    private var debounceJob: Job? = null

    /** Bumped whenever the editor switches to another project, so late saves do not claim the new one. */
    private var projectGen = 0
    private var lastFingerprint: Int? = null

    /** Cheap hash of layer structure/properties (not strokes, not selection). Reads Compose state. */
    private fun layerFingerprint(): Int {
        var h = project.layers.size
        h = 31 * h + anim.durationSec.hashCode() // animation length is project content too
        for (l in project.layers) {
            h = 31 * h + l.id.hashCode()
            h = 31 * h + l.name.hashCode()
            h = 31 * h + (if (l.visible) 1 else 0)
            h = 31 * h + l.opacity.hashCode()
            h = 31 * h + (if (l.locked) 1 else 0)
            h = 31 * h + (if (l.inAnimation) 1 else 0)
            h = 31 * h + l.animPriority
            h = 31 * h + l.kind.ordinal
        }
        return h
    }

    /** Runs until cancelled (call from a LaunchedEffect): layer property edits made in the UI trigger autosave. */
    suspend fun watchLayerChanges() {
        snapshotFlow { layerFingerprint() }.collect { fp ->
            val prev = lastFingerprint
            lastFingerprint = fp
            if (prev != null && prev != fp) markDirty()
        }
    }

    // A project with only an imported photo (no strokes yet) is still worth keeping.
    private fun shouldSave(): Boolean =
        session.projectId != null || project.background != null || project.layers.any { it.strokes.isNotEmpty() }

    /** Call after any content mutation: saves 2 s after the last call. Ignored while a project is loading. */
    fun markDirty() {
        if (projectLoad == ProjectLoad.Loading) return
        dirty = true
        debounceJob?.cancel()
        debounceJob = viewModelScope.launch {
            delay(AUTOSAVE_DELAY_MS)
            submitSave("autosave") // no suspension inside, so a later markDirty cannot cancel half a save
        }
    }

    /** Takes the snapshot now (main thread) and queues it on the session; the write happens in order, off-thread. */
    private fun submitSave(reason: String) {
        if (!dirty) return
        if (session.load.value == ProjectLoad.Loading) return
        if (!shouldSave()) {
            dirty = false
            return
        }
        dirty = false
        session.setName(projectName)
        session.requestSave(project.toSnapshot(anim.durationSec), project.background, reason)
    }

    /** Persists right now (used for a freshly imported photo, which has no strokes to trigger autosave). */
    private fun persistNow(reason: String) {
        dirty = true
        debounceJob?.cancel()
        submitSave(reason)
    }

    /** Saves pending changes immediately, then reloads the recent list (used when leaving the editor). */
    fun saveAndRefresh() {
        debounceJob?.cancel()
        submitSave("leave")
        refreshRecent()
    }

    private var recentGen = 0

    /** Reloads the recent list after every queued write; a newer refresh makes an older one's result get dropped. */
    fun refreshRecent() {
        val gen = ++recentGen
        viewModelScope.launch {
            session.flush(retryFailed = false)
            val list = withContext(Dispatchers.IO) { repo.list().map { it.toUi() } }
            if (gen == recentGen) recentProjects = list
        }
    }

    private fun ProjectMeta.toUi(): RecentProjectUi {
        val thumb = thumbPath?.let { path ->
            try {
                android.graphics.BitmapFactory.decodeFile(path)?.asImageBitmap()
            } catch (t: Throwable) {
                null
            }
        }
        return RecentProjectUi(id, name, thumb, updatedLabel(updatedAt))
    }

    private fun updatedLabel(t: Long): String {
        val now = System.currentTimeMillis()
        val diff = (now - t).coerceAtLeast(0L)
        fun startOfDay(ms: Long): Long = Calendar.getInstance().apply {
            timeInMillis = ms
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val days = Math.round((startOfDay(now) - startOfDay(t)) / 86_400_000.0)
        return when {
            diff < 60_000L -> "Just now"
            days == 0L && diff < 3_600_000L -> "${diff / 60_000L} min ago"
            days == 0L -> "${diff / 3_600_000L} h ago"
            days == 1L -> "Yesterday"
            else -> java.text.SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(t))
        }
    }

    fun openProject(id: String) {
        debounceJob?.cancel()
        submitSave("open") // queue the open project's edits first; the id is captured, so order on disk is safe
        val token = session.beginLoad() // editing is blocked until Ready; a newer open/reset makes this one stale
        viewModelScope.launch {
            if (!session.flush()) toast("Could not save the previous project yet; will keep retrying")
            val loaded = repo.load(id)
            val name = if (loaded != null) repo.list().firstOrNull { it.id == id }?.name ?: DEFAULT_PROJECT_NAME else null
            if (!session.isCurrentLoad(token)) return@launch // stale: something newer won
            if (loaded == null) {
                session.failLoad(token)
                toast("Could not open that project")
                refreshRecent()
                return@launch
            }
            resetProject(keepLoad = true)
            val snap = loaded.snapshot
            project.applySnapshot(snap)
            if (project.layers.isEmpty()) project.layers.add(project.newLayer("Layer 1"))
            project.background = loaded.source
            sourceBitmap = loaded.source
            autoSource = sourceSerial // loaded auto layers belong to this project's own source
            if (loaded.source != null) model().warmUp()
            sourceThumbnail = loaded.source?.let { makeThumbnail(it) }
            if (snap.animationSeconds > 0f) anim.durationSec = snap.animationSeconds
            hasProject = true
            styleSheetRequest = false
            session.completeLoad(token, id, name!!)
            projectName = name
            dirty = false
            lastFingerprint = layerFingerprint()
            anim.showFinished()
        }
    }

    fun deleteProject(id: String) {
        if (id == session.projectId) {
            debounceJob?.cancel()
            dirty = false
        }
        val done = session.delete(id) // tombstoned immediately; queued saves of it are discarded
        viewModelScope.launch {
            done.await()
            refreshRecent()
        }
    }

    fun renameProject(name: String) {
        val n = name.trim().take(80)
        if (n.isEmpty()) return
        projectName = n
        session.setName(n)
        val id = session.projectId
        if (id == null) {
            markDirty()
        } else {
            val done = session.rename(id, n)
            viewModelScope.launch {
                done.await()
                refreshRecent()
            }
        }
    }

    // ------------------------------------------------------------------ import / generate

    private var poseMode = false
    private var settingsBeforePose: PipelineSettings? = null

    /** Pose renders: clean outline only, no shading, ignore background. Off restores the previous settings. */
    fun setPoseMode(on: Boolean) {
        if (on) {
            if (!poseMode) settingsBeforePose = pipelineSettings
            poseMode = true
            pipelineSettings = pipelineSettings.copy(
                lineSource = LineSource.AUTO,
                colourFill = false,
                shading = ShadingLevel.NONE,
                detail = 0.45f,
                ignoreBackground = true,
            )
        } else if (poseMode) {
            poseMode = false
            settingsBeforePose?.let { pipelineSettings = it }
            settingsBeforePose = null
        }
    }

    /** "<prefix> <n>" with n one above the highest existing "<prefix> <k>" in the recent list. */
    private fun autoName(prefix: String): String {
        val max = recentProjects.mapNotNull { r ->
            r.name.removePrefix("$prefix ").takeIf { r.name.startsWith("$prefix ") }?.trim()?.toIntOrNull()
        }.maxOrNull() ?: 0
        return "$prefix ${max + 1}"
    }

    private fun displayNameOf(uri: Uri): String? = try {
        app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }?.substringBeforeLast('.')?.trim()?.take(80)?.takeIf { it.isNotEmpty() }
    } catch (t: Throwable) {
        null
    }

    fun renameProjectById(id: String, name: String) {
        val n = name.trim().take(80)
        if (n.isEmpty()) return
        if (id == session.projectId) projectName = n
        val done = session.rename(id, n)
        viewModelScope.launch {
            done.await()
            refreshRecent()
        }
    }

    /** [pose] is part of the request: pose mode is applied now and reset by every other load/open. */
    fun loadImage(uri: Uri, pose: Boolean = false) {
        setPoseMode(pose)
        val armed = takeArmedActions()
        viewModelScope.launch {
            val picked = withContext(Dispatchers.IO) { displayNameOf(uri) }
            val bmp = withContext(Dispatchers.IO) {
                try {
                    ImageLoader.load(app, uri, settings.maxSide)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) { // incl. OutOfMemoryError
                    null
                }
            }
            if (bmp == null) {
                toast("Could not open that image")
                if (pose) setPoseMode(false)
                return@launch
            }
            flushPendingSave()
            resetProject()
            sessionActions = armed
            projectName = picked ?: autoName(if (poseMode) "Pose" else "Sketch")
            setSource(bmp)
            model().warmUp()
            emitEditorEvent(EditorEvent.OpenStyle(projectGen))
        }
    }

    /** Loads an asset (default assets/demo.png) and generates + animates it like an imported photo. */
    fun runDemo(asset: String = "demo.png") {
        setPoseMode(false)
        val armed = takeArmedActions()
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                try {
                    ImageLoader.loadAsset(app, asset, settings.maxSide)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    null
                }
            }
            if (bmp == null) {
                toast("Could not open demo image")
                return@launch
            }
            flushPendingSave()
            resetProject()
            sessionActions = armed
            projectName = if (recentProjects.none { it.name == "Demo" }) "Demo" else autoName("Demo")
            setSource(bmp)
            model().warmUp()
            generate()
        }
    }

    /** Starts an empty project (no photo, one blank layer). */
    fun newProject() {
        setPoseMode(false)
        viewModelScope.launch {
            flushPendingSave()
            resetProject()
            sourceBitmap = null
            sourceThumbnail = null
            hasProject = false
            project.background = null
            project.width = 1024
            project.height = 1024
            projectName = autoName("Sketch")
        }
    }

    /** Saves unsaved edits of the open project before it is replaced (cancels the debounce first). */
    private suspend fun flushPendingSave() {
        debounceJob?.cancel()
        submitSave("switch")
        if (!session.flush()) toast("Could not save the previous project yet; will keep retrying")
        refreshRecent()
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
        model: String? = null,
        accel: String? = null,
        outline: String? = null,
        label: String? = null,
    ) {
        if (label != null) DebugDump.runLabel = label
        armedActions = buildSet {
            if (autoVideo) add(EditorAutoAction.AUTO_VIDEO)
            if (showLayers) add(EditorAutoAction.SHOW_LAYERS)
            if (autoExport) add(EditorAutoAction.AUTO_EXPORT)
        }
        if (!autodemo && armedActions.isNotEmpty()) {
            // No new project is coming: the actions belong to the project that is open now.
            sessionActions = sessionActions + takeArmedActions()
            if (hasProject && !generating) fireSessionActions()
        }
        when (mode?.lowercase()) {
            "outline" -> settings = settings.copy(mode = DrawMode.OUTLINE)
            "shading" -> settings = settings.copy(mode = DrawMode.OUTLINE_SHADING)
            "color" -> settings = settings.copy(mode = DrawMode.COLOUR_FILL)
        }
        applyIntentOptions(engine, shading)
        when (outline?.lowercase()) {
            "pencil" -> pipelineSettings = pipelineSettings.copy(outlineStyle = com.smitnk.sketcher.image.pipeline.OutlineStyle.PENCIL_REVEAL)
            "vector" -> pipelineSettings = pipelineSettings.copy(outlineStyle = com.smitnk.sketcher.image.pipeline.OutlineStyle.CLEAN_VECTOR)
        }
        val start = {
            if (autodemo) runDemo(
                when {
                    testImage != null -> "testimages/$testImage.jpg"
                    demo == "color" -> "demo_color.png"
                    else -> "demo.png"
                }
            )
        }
        if (model == null && accel == null) { start(); return }
        viewModelScope.launch {
            try {
                val spec = model?.lowercase()?.let { models.spec(it) }
                if (spec != null) {
                    val ok = withContext(Dispatchers.IO) { models.isAvailable(spec.id) }
                    if (ok) modelStates.update { ModelStates.installed(it, spec.id) }
                    else {
                        downloadLineModel(spec.id)
                        var waited = 0
                        while (spec.id !in installedModelIds && modelStates.value[spec.id] !is ModelState.Failed && waited < 85_000) { delay(500); waited += 500 }
                    }
                    selectLineModel(spec.id)
                }
                accel?.lowercase()?.let { selectAccel(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                android.util.Log.w("SketcherGen", "intent model/accel setup failed", t)
            }
            start()
        }
    }

    private fun takeArmedActions(): Set<EditorAutoAction> = armedActions.also { armedActions = emptySet() }

    private fun resetProject(keepLoad: Boolean = false) {
        debounceJob?.cancel()
        dirty = false
        projectGen++
        projectEpoch++
        liveSession = null
        sessionActions = emptySet()
        drainEditorEvents()
        // Drop any running generation: its result belongs to the project being replaced.
        // The job is kept so the next generate() can join it (and wait for the engine) before starting.
        generateJob?.cancel()
        cancelEngineRuns()
        genMachine.reset()
        publishGen()
        generationCount = 0
        if (!keepLoad) session.reset() // new project: id allocated on its first save; cancels any pending load
        projectName = DEFAULT_PROJECT_NAME
        project.layers.clear()
        project.layers.add(project.newLayer("Layer 1"))
        project.selectedIndex = 0
        project.background = null
        sourceSerial++
        autoSource = sourceSerial
        clearHistory()
        anim.showFinished()
    }

    private fun makeThumbnail(bmp: Bitmap): ImageBitmap? = try {
        val m = max(bmp.width, bmp.height).coerceAtLeast(1)
        val k = 320f / m
        if (k >= 1f) bmp.asImageBitmap()
        else Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true).asImageBitmap()
    } catch (t: Throwable) {
        null
    }

    /** Increments for every newly imported/loaded source photo; auto layers remember which one produced them. */
    private var sourceSerial = 0
    private var autoSource = 0

    private fun setSource(bmp: Bitmap) {
        sourceSerial++
        sourceBitmap = bmp
        sourceThumbnail = makeThumbnail(bmp)
        hasProject = true
        project.width = bmp.width
        project.height = bmp.height
        project.background = bmp
        project.backgroundVisible = true
        project.backgroundOpacity = 1f
        persistNow("import")
    }

    fun generate() {
        val bmp = sourceBitmap
        if (bmp == null) {
            toast("Import a photo first")
            return
        }
        if (benchmarking) {
            toast("Wait for the speed test to finish")
            return
        }
        anim.showFinished()
        val genStart = android.os.SystemClock.elapsedRealtime()
        val gen = projectGen
        val prev = generateJob
        // Supersede any running generation at once: its callbacks carry the old id and are dropped from here on.
        val run = genMachine.begin(genStart)
        publishGen()
        cancelEngineRuns()
        prev?.cancel()
        generateJob = viewModelScope.launch {
            var token = 0L
            var success = false
            try {
                // One generation at a time: wait for the previous one (and its model) to let go of the engine.
                if (prev != null && withTimeoutOrNull(PREV_JOIN_MS) { prev.join() } == null) {
                    MlLog.w("previous generation did not stop in time: resetting the AI engine")
                    OnnxLineArtModel.tripBreaker()
                }
                if (!genMachine.isCurrent(run)) return@launch
                closeInBackground(runFallback)
                runFallback = null
                runFellBackTo = null
                runFallbackReason = null
                flushDeferred()
                val released = withContext(Dispatchers.Default) { OnnxLineArtModel.awaitRelease(ENGINE_RELEASE_WAIT_MS) }
                if (!released) {
                    MlLog.w("engine still held after previous run: resetting it")
                    OnnxLineArtModel.tripBreaker()
                }
                val ps = pipelineSettings
                val m = model()
                token = m.beginRun()
                m.detail = ps.detail
                m.longSide = SketchPipeline.modelSide(ps.detail)
                genStartMs = genStart
                stageStartMs = genStart
                stageElapsedSec = 0
                MlLog.i("generate begin run=$run model=${selectedLineModelId} accel=$selectedAccel src=${bmp.width}x${bmp.height}")
                val ticker = launch {
                    while (true) {
                        kotlinx.coroutines.delay(1000)
                        stageElapsedSec = ((android.os.SystemClock.elapsedRealtime() - stageStartMs) / 1000).toInt()
                    }
                }
                // Cancelling this coroutine must stop the blocking ONNX run too, not just abandon the Job.
                val stopper = launch {
                    try { awaitCancellation() } finally { if (!success) { m.cancelRun(token); runFallback?.cancelRun() } }
                }
                val (drawing, scaledBg) = try { withContext(Dispatchers.Default) {
                    val w = bmp.width
                    val h = bmp.height
                    val pixels = IntArray(w * h)
                    bmp.getPixels(pixels, 0, w, 0, 0, w, h)
                    val d = SketchPipeline.run(pixels, w, h, ps, DebugDump.wrap(app, withFallback(m, run)), { st, f -> onStage(run, st, f) })
                    val bg = if (w != d.width || h != d.height) {
                        Bitmap.createScaledBitmap(bmp, d.width, d.height, true)
                    } else {
                        null
                    }
                    d to bg
                } } finally { ticker.cancel(); stopper.cancel() }
                if (gen != projectGen || !genMachine.isCurrent(run)) return@launch // superseded / another project
                lastAppliedSettings = ps
                refreshMlStatus()
                runFallbackReason?.let { fallbackReason = it }
                applyDrawing(drawing, scaledBg)
                android.util.Log.i(
                    "SketcherGen",
                    "GEN_DONE ms=${android.os.SystemClock.elapsedRealtime() - genStart} " +
                        "outline=${drawing.outline.size} shading=${drawing.shading.size} fill=${drawing.fill.size} " +
                        "size=${drawing.width}x${drawing.height} settings=$ps",
                )
                if (DebugDump.enabled(app)) {
                    val snap = project.layers.toList()
                    DebugDump.layersRef = project.layers
                    val genMs = android.os.SystemClock.elapsedRealtime() - genStart
                    val a = activeAccelLabel; val fb = fallbackReason; val inf = lastInferenceMs; val mid = selectedLineModelId
                    withContext(Dispatchers.Default) {
                        DebugDump.afterGeneration(app, drawing, bmp, ps, snap, mid, a, fb, inf, genMs, SketchPipeline.lastLineSource)
                    }
                }
                MlLog.i("generate done in ${android.os.SystemClock.elapsedRealtime() - genStart} ms source=${SketchPipeline.lastLineSource} timings=${SketchPipeline.lastTimings}")
                success = true
                genMachine.finish(run)
                publishGen()
                generationCount++
                markDirty()
                if (pendingAutoVideo) {
                    pendingAutoVideo = false
                    autoVideo()
                }
            } catch (e: GenerationAbort) {
                // Engine busy / model closed: the run fails, the user's model selection is untouched.
                MlLog.w("generate aborted: ${e.reason}")
                pendingAutoVideo = false
                if (genMachine.fail(run, e.reason, runFellBackTo)) { publishGen(); toast(e.reason) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                android.util.Log.e("SketcherGen", "GEN_FAIL", t)
                MlLog.w("generate failed", t)
                pendingAutoVideo = false // a failed run must not leave the auto-video request armed for the next one
                val msg = if (t is OutOfMemoryError) "Not enough memory - try a smaller image size in Settings"
                else "Generation failed: ${t.message}"
                if (genMachine.fail(run, msg, runFellBackTo)) publishGen()
                toast(msg)
            } finally {
                // Cancelled while still the current run (project reset / teardown): go back to Idle.
                if (gen == projectGen && genMachine.isCurrent(run)) genMachine.reset()
                publishGen()
                if (!generating) {
                    closeInBackground(runFallback)
                    runFallback = null
                    flushDeferred()
                }
            }
        }
    }

    private var generateJob: Job? = null
    private var lastAppliedSettings = PipelineSettings()

    /** Stacking order of generated layers, bottom to top. */
    private fun autoRank(k: LayerKind): Int = when (k) {
        LayerKind.AUTO_FILL -> 0
        LayerKind.AUTO_OUTLINE -> 1
        LayerKind.AUTO_SHADING -> 2
        LayerKind.MANUAL -> 3
    }

    /**
     * Replaces only the auto-generated layers (kind AUTO_*) in place, keeping their position, visibility, name
     * and opacity. Manual layers and their order are untouched. Newly needed auto layers go below the manual ones.
     */
    private fun applyDrawing(drawing: GeneratedDrawing, scaledBackground: Bitmap?) {
        project.width = drawing.width
        project.height = drawing.height
        if (scaledBackground != null) project.background = scaledBackground
        val ps = lastAppliedSettings
        val previousSelected = project.selectedLayer
        // Never carry generated layers over from a different source photo.
        val result = LayerReconcile.keepForGeneration(project.layers.toList(), autoSource, sourceSerial).toMutableList()
        autoSource = sourceSerial

        // kind -> (default name, strokes or null when this run does not produce that kind), bottom to top.
        val wanted = listOf(
            Triple(LayerKind.AUTO_FILL, "Colour Fill", if (ps.colourFill) drawing.fill else null),
            Triple(LayerKind.AUTO_OUTLINE, "Outline", drawing.outline),
            Triple(LayerKind.AUTO_SHADING, "Shading", if (ps.shading != ShadingLevel.NONE) drawing.shading else null),
        )
        for ((kind, name, strokes) in wanted) {
            val existing = result.filter { it.kind == kind }
            if (strokes == null) {
                result.removeAll(existing) // no longer produced by the current settings
                continue
            }
            result.removeAll(existing.drop(1)) // duplicates
            val layer = existing.firstOrNull() ?: project.newLayer(name).also {
                it.kind = kind
                val below = result.indexOfLast { l -> l.kind != LayerKind.MANUAL && autoRank(l.kind) < autoRank(kind) }
                result.add(below + 1, it)
            }
            layer.strokes.clear()
            layer.strokes.addAll(strokes)
            if (kind == LayerKind.AUTO_OUTLINE) {
                val inkPx = drawing.outlineInk
                if (inkPx != null && drawing.inkW > 0 && drawing.inkH > 0 && inkPx.size >= drawing.inkW * drawing.inkH) {
                    layer.inkTexture = Bitmap.createBitmap(inkPx, drawing.inkW, drawing.inkH, Bitmap.Config.ARGB_8888)
                    layer.revealWidthPx = drawing.revealWidth
                } else {
                    layer.inkTexture = null
                    layer.revealWidthPx = 0f
                }
            }
            layer.animPriority = if (kind == LayerKind.AUTO_OUTLINE && ps.colourFill) -1 else 0
        }
        if (result.none { it.kind == LayerKind.MANUAL }) {
            result.add(project.newLayer("My Drawing"))
        }
        val manual = result.filter { it.kind == LayerKind.MANUAL }
        val first = manual.singleOrNull()
        if (first != null && first.strokes.isEmpty() && first.name == "Layer 1") first.name = "My Drawing"

        if (result != project.layers.toList()) {
            project.layers.clear()
            project.layers.addAll(result)
        }
        val sel = if (previousSelected != null && previousSelected.kind == LayerKind.MANUAL && previousSelected in result) {
            previousSelected
        } else {
            manual.last()
        }
        project.selectedIndex = result.indexOf(sel).coerceAtLeast(0)
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
        markDirty()
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
        markDirty()
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
        markDirty()
    }

    // ------------------------------------------------------------------ drawing

    private fun layerById(id: Long): Layer? = project.layers.firstOrNull { it.id == id }

    /**
     * Starts a stroke/erase on the selected layer, freezing tool/brush/colour/size/alpha. Returns null (and toasts
     * once) when the layer cannot take it (locked, hidden, pencil-texture + brush). Call once per gesture.
     */
    fun beginStroke(x: Float, y: Float): StrokeSession? {
        liveSession?.let { cancelStroke(it) }
        val layer = project.selectedLayer ?: return null
        if (layer.locked || !layer.visible) {
            toast(if (layer.locked) "This layer is locked" else "This layer is hidden")
            return null
        }
        if (anim.active) anim.showFinished()
        var inkBefore: Bitmap? = null
        if (layer.inkTexture != null) {
            if (tool == Tool.BRUSH) {
                toast("This is a pencil-texture layer - draw on another layer, or use the eraser")
                return null
            }
            inkBefore = layer.inkTexture?.copy(Bitmap.Config.ARGB_8888, true)
        }
        val session = StrokeSession(layer.id, tool, brushType, brushColor, brushSize, brushAlpha, inkBefore)
        liveSession = session
        if (session.tool == Tool.BRUSH) session.addPoint(x, y) else eraseAt(session, layer, x, y)
        liveTick++
        return session
    }

    fun extendStroke(session: StrokeSession, x: Float, y: Float) {
        if (session !== liveSession) return
        if (session.tool == Tool.BRUSH) {
            session.addPoint(x, y)
        } else {
            val layer = layerById(session.layerId) ?: return
            eraseAt(session, layer, x, y)
        }
        liveTick++
    }

    /** Commits the stroke to the layer it started on (looked up by id; dropped if that layer is gone). */
    fun endStroke(session: StrokeSession) {
        if (session !== liveSession) return
        liveSession = null
        val layer = layerById(session.layerId)
        if (layer != null) {
            if (session.tool == Tool.BRUSH) {
                val stroke = session.toStroke()
                if (stroke != null) {
                    layer.strokes.add(stroke)
                    pushUndo(AddStrokeAction(layer, stroke))
                }
            } else if (layer.inkTexture != null) {
                val before = session.inkBefore
                val after = layer.inkTexture
                if (before != null && after != null && session.inkErased) {
                    pushUndo(InkEraseAction(layer, before, after.copy(Bitmap.Config.ARGB_8888, true)))
                }
            } else if (session.erased.isNotEmpty()) {
                pushUndo(EraseAction(layer, session.erased))
            }
        }
        liveTick++
    }

    /** Discards the stroke with no undo entry; an eraser's effects so far are reverted. Safe to call repeatedly. */
    fun cancelStroke(session: StrokeSession) {
        if (session !== liveSession) return
        liveSession = null
        val layer = layerById(session.layerId)
        if (layer != null && session.tool == Tool.ERASER) {
            if (layer.inkTexture != null) {
                val before = session.inkBefore
                if (before != null && session.inkErased) layer.inkTexture = before
            } else if (session.erased.isNotEmpty()) {
                EraseAction(layer, session.erased).undo()
            }
        }
        liveTick++
    }

    /** The brush stroke being drawn right now, if any. */
    fun liveStroke(): Stroke? = liveSession?.liveStroke()

    val liveLayerId: Long get() = liveSession?.layerId ?: -1L

    private val clearPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
    }

    /** Eraser on a pencil-reveal layer: CLEAR along the path into the (mutable) ink texture. */
    private fun eraseInk(session: StrokeSession, layer: Layer, x: Float, y: Float) {
        var tex = layer.inkTexture ?: return
        if (!tex.isMutable) {
            tex = tex.copy(Bitmap.Config.ARGB_8888, true)
            layer.inkTexture = tex
        }
        val radius = max(session.size * 1.5f, 6f)
        clearPaint.strokeWidth = radius * 2f
        val c = android.graphics.Canvas(tex)
        if (session.lastEraseX.isNaN()) c.drawPoint(x, y, clearPaint) else c.drawLine(session.lastEraseX, session.lastEraseY, x, y, clearPaint)
        session.lastEraseX = x; session.lastEraseY = y
        session.inkErased = true
        layer.inkRev++
    }

    private fun eraseAt(session: StrokeSession, layer: Layer, x: Float, y: Float) {
        if (layer.inkTexture != null) { eraseInk(session, layer, x, y); return }
        val radius = max(session.size * 1.5f, 6f)
        var i = 0
        while (i < layer.strokes.size) {
            val s = layer.strokes[i]
            if (strokeNear(s, x, y, radius + s.width / 2f)) {
                session.erased.add(i to s)
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
        trimHistory()
        syncHistoryFlags()
        markDirty()
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        val a = undoStack.removeAt(undoStack.lastIndex)
        a.undo()
        redoStack.add(a)
        syncHistoryFlags()
        markDirty()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        val a = redoStack.removeAt(redoStack.lastIndex)
        a.redo()
        undoStack.add(a)
        syncHistoryFlags()
        markDirty()
    }

    /** Keeps at most [MAX_UNDO] entries and ~[MAX_HISTORY_BYTES] of bitmap snapshots, dropping the oldest. */
    private fun trimHistory() {
        var bytes = undoStack.sumOf { it.cost }
        while (undoStack.size > 1 && (undoStack.size > MAX_UNDO || bytes > MAX_HISTORY_BYTES)) {
            bytes -= undoStack.removeAt(0).cost
        }
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
                it.animPriority = src.animPriority
                it.locked = src.locked
                it.kind = src.kind
                it.strokes.addAll(src.strokes.toList())
                it.revealWidthPx = src.revealWidthPx
                it.inkTexture = src.inkTexture?.takeIf { b -> !b.isRecycled }?.copy(Bitmap.Config.ARGB_8888, false)
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

    /** One image to render and save. [scale]: 1/2/4 or [Exporter.SCALE_ORIGINAL]. */
    private class ImageJob(
        val layers: List<Layer>,
        val w: Int,
        val h: Int,
        val scale: Int,
        val fmt: ExportFormat,
        val transparent: Boolean,
        val ignoreState: Boolean,
        val name: String,
        val origW: Int,
        val origH: Int,
    )

    private fun setRunning(progress: Float? = null, label: String? = null) {
        val st = exportState as? ExportState.Running ?: return
        exportState = st.copy(progress = progress ?: st.progress, label = label ?: st.label)
    }

    /**
     * Starts an export on a background coroutine. Refuses (with a toast) while generating or while another
     * export runs; otherwise it explicitly replaces any previous Done/Failed state. [work] returns one result
     * per file; a thrown CancellationException means the user cancelled (exporters delete their pending file).
     */
    private fun startExport(kind: ExportKind, label: String, work: suspend CoroutineScope.(done: MutableList<ExportResult>) -> Unit): Boolean {
        exportBlockedReason?.let { toast(it); return false }
        exportState = ExportState.Running(kind, 0f, label)
        exportJob = viewModelScope.launch {
            val done = ArrayList<ExportResult>()
            try {
                withContext(Dispatchers.Default) { work(this, done) }
                exportState = if (done.isEmpty()) ExportState.Failed("Nothing was exported") else ExportState.Done(done.toList())
            } catch (e: CancellationException) {
                exportState = ExportState.Idle
                val kept = done.count { it.ok }
                toast(if (kept > 0) "Export cancelled ($kept file(s) were already saved)" else "Export cancelled")
                throw e
            } catch (t: Throwable) {
                exportState = ExportState.Failed(t.message ?: "Export failed")
            }
        }
        return true
    }

    private fun runImageJob(job: ImageJob, kind: ExportKind, check: () -> Unit): ExportResult = try {
        val bmp = Exporter.render(job.w, job.h, job.layers, job.scale, job.transparent, job.ignoreState, job.origW, job.origH)
        val where = try {
            check()
            Exporter.save(app, bmp, job.fmt, job.name, check)
        } finally {
            bmp.recycle()
        }
        if (where != null) {
            ExportResult(kind, resolveSavedUri(where, job.fmt.mime)?.toString(), where, true)
        } else {
            ExportResult(kind, null, null, false, "Could not write the ${job.fmt.extension.uppercase()} file")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        ExportResult(kind, null, null, false, if (t is OutOfMemoryError) "Not enough memory, try a smaller size" else (t.message ?: "unknown error"))
    }

    private fun startImageExport(kind: ExportKind, label: String, jobs: List<ImageJob>) {
        startExport(kind, label) { done ->
            for ((i, job) in jobs.withIndex()) {
                ensureActive()
                done.add(runImageJob(job, kind) { ensureActive() })
                setRunning(progress = (i + 1f) / jobs.size)
            }
        }
    }

    private fun originalSize(): Pair<Int, Int> = sourceBitmap?.let { it.width to it.height } ?: (0 to 0)

    /** Size of the imported photo (0 when none): what the "Original" export size maps to. */
    val originalPhotoWidth: Int get() = sourceBitmap?.width ?: 0
    val originalPhotoHeight: Int get() = sourceBitmap?.height ?: 0

    /** One file per visible layer. PNG = transparent per layer; JPEG = on paper (JPEG has no transparency). */
    fun exportLayersSeparately(scale: Int, format: ExportFormat, transparent: Boolean) {
        exportBlockedReason?.let { toast(it); return }
        val layers = project.layers.filter { it.visible && it.strokes.isNotEmpty() }
        if (layers.isEmpty()) {
            toast("Nothing to export yet")
            return
        }
        val (ow, oh) = originalSize()
        val w = project.width
        val h = project.height
        val jobs = layers.map { l ->
            ImageJob(snapshotLayers(l), w, h, scale, format, format == ExportFormat.PNG, true, l.name, ow, oh)
        }
        startImageExport(ExportKind.LAYERS, "Saving layers...", jobs)
    }

    fun export(scale: Int, format: ExportFormat, transparent: Boolean, selectedLayerOnly: Boolean) {
        exportBlockedReason?.let { toast(it); return }
        val layer = if (selectedLayerOnly) project.selectedLayer else null
        if (selectedLayerOnly && layer == null) return
        val isLayer = layer != null
        val fmt = if (isLayer) ExportFormat.PNG else format
        val (ow, oh) = originalSize()
        val job = ImageJob(
            snapshotLayers(layer), project.width, project.height, scale, fmt,
            transparent = isLayer || (fmt == ExportFormat.PNG && transparent),
            ignoreState = isLayer, name = layer?.name ?: "drawing", origW = ow, origH = oh,
        )
        startImageExport(ExportKind.IMAGE, "Saving image...", listOf(job))
    }

    /**
     * Exports all visible layers as PNG, then the Outline layer alone as transparent PNG, as ONE export.
     * Waits until no export runs and generation is finished (never wipes a result that is showing).
     */
    fun autoExport() {
        viewModelScope.launch {
            snapshotFlow { exportBlockedReason }.first { it == null }
            val outline = project.layers.firstOrNull { it.kind == LayerKind.AUTO_OUTLINE }
            val (ow, oh) = originalSize()
            val w = project.width
            val h = project.height
            val jobs = ArrayList<ImageJob>()
            jobs.add(ImageJob(snapshotLayers(null), w, h, 1, ExportFormat.PNG, false, false, "drawing", ow, oh))
            if (outline != null) jobs.add(ImageJob(snapshotLayers(outline), w, h, 1, ExportFormat.PNG, true, true, "Outline", ow, oh))
            startImageExport(ExportKind.IMAGE, "Saving image...", jobs)
        }
    }

    /** Exports the draw-along animation as MP4 or GIF on a background thread, with progress + cancel. */
    fun exportAnimation(kind: AnimExportKind) = startAnimationExport(listOf(kind))

    /** For CI: after generation, exports both MP4 and GIF (waits for any running export first). */
    fun autoVideo() {
        viewModelScope.launch {
            snapshotFlow { exportBlockedReason }.first { it == null }
            startAnimationExport(listOf(AnimExportKind.VIDEO, AnimExportKind.GIF))
        }
    }

    fun cancelExport() {
        if (exportState is ExportState.Running) exportJob?.cancel()
    }

    private fun startAnimationExport(kinds: List<AnimExportKind>) {
        exportBlockedReason?.let { toast(it); return }
        if (project.layers.all { it.strokes.isEmpty() }) {
            toast("Nothing to export yet")
            return
        }
        val layers = snapshotLayers(null)
        val w = project.width
        val h = project.height
        val duration = anim.durationSec
        val first = if (kinds.first() == AnimExportKind.VIDEO) ExportKind.VIDEO else ExportKind.GIF
        startExport(first, "Preparing...") { done ->
            for ((i, k) in kinds.withIndex()) {
                ensureActive()
                val kind = if (k == AnimExportKind.VIDEO) ExportKind.VIDEO else ExportKind.GIF
                setRunning(label = kind.runningLabel)
                var last = -1f
                val onProgress = { p: Float ->
                    val overall = (i + p) / kinds.size
                    if (overall - last >= 0.005f || overall >= 1f) { last = overall; setRunning(progress = overall) }
                }
                val check = { ensureActive() }
                try {
                    val where = if (k == AnimExportKind.VIDEO) {
                        VideoExporter.export(app, w, h, layers, duration, "animation", onProgress, check)
                    } else {
                        GifExporter.export(app, w, h, layers, duration, "animation", onProgress, check)
                    }
                    val mime = if (k == AnimExportKind.VIDEO) "video/mp4" else "image/gif"
                    done.add(
                        if (where != null) ExportResult(kind, resolveSavedUri(where, mime)?.toString(), where, true)
                        else ExportResult(kind, null, null, false, "Could not write the ${kind.label} file"),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    done.add(ExportResult(kind, null, null, false, t.message ?: "unknown error"))
                }
            }
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(app, msg, Toast.LENGTH_LONG).show()
    }

    // Startup work runs LAST so every property above is initialised first
    // (Kotlin runs initializers in declaration order; Main.immediate coroutines execute synchronously).
    init {
        refreshInstalledModels()
        refreshRecent()
    }
}
