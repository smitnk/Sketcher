package com.smitnk.sketcher.image.pipeline

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import android.content.Context
import android.os.Debug
import android.util.Log
import com.smitnk.sketcher.image.models.ModelSpec
import com.smitnk.sketcher.image.models.ModelSpecs
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.EnumSet
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Execution provider preference. NNAPI_* differ only in flags: ORT cannot pick the NNAPI device (Android decides
 * between GPU, NPU/DSP and its own CPU reference), so the UI presents both as one "NNAPI accelerator" option.
 */
enum class Accel(val label: String) {
    CPU("CPU"),
    XNNPACK("CPU + XNNPACK"),
    NNAPI_GPU(NNAPI_LABEL),
    NNAPI_NPU(NNAPI_LABEL),
}

/** Honest name for the NNAPI provider: which hardware actually runs it is up to the phone's driver. */
const val NNAPI_LABEL = "NNAPI accelerator (GPU/NPU)"

/** Stage/timing/memory log under one tag ("SketcherML") so a bug report contains real numbers. */
object MlLog {
    const val TAG = "SketcherML"

    fun mem(): String {
        val rt = Runtime.getRuntime()
        val used = (rt.totalMemory() - rt.freeMemory()) shr 20
        return "java=${used}/${rt.maxMemory() shr 20}MB native=${Debug.getNativeHeapAllocatedSize() shr 20}MB"
    }

    fun i(msg: String) { Log.i(TAG, "$msg [${mem()}]") }
    fun w(msg: String, t: Throwable? = null) { if (t != null) Log.w(TAG, "$msg [${mem()}]", t) else Log.w(TAG, "$msg [${mem()}]") }
}

/** The AI step did not finish in time (hung driver / far too slow); callers fall back to the bundled model. */
class InferenceTimeoutException(message: String) : RuntimeException(message)

/**
 * ONNX Runtime line-art model driven by a [ModelSpec] (normalisation, output polarity, size multiple).
 * [infer] returns an ink map (1 = ink). Weights come from [modelFile] if given, else the spec's bundled asset.
 * Throws on failure so the caller can fall back.
 *
 * Safety rules (a phone-level UI crash was traced to GPU/NNAPI + fp16 + big inputs + several sessions):
 *  - at most ONE OrtSession exists process-wide (a global lock; creating one evicts the previous owner's);
 *  - NNAPI is never used for fp16 models or for inputs above [NNAPI_MAX_PIXELS];
 *  - every inference runs on a dedicated thread with a [TIMEOUT_MS] watchdog;
 *  - input size is capped by [ModelSpec.maxPixels] and never padded to a square.
 */
class OnnxLineArtModel(
    context: Context,
    private val spec: ModelSpec = ModelSpecs.INFORMATIVE,
    private val modelFile: File? = null,
    /** Inference long side (768 default; 1024 allowed). Rounded to the spec's size multiple and capped by maxPixels. */
    @Volatile var longSide: Int = DEFAULT_LONG_SIDE,
    /** Null = last persisted choice, else XNNPACK. */
    accel: Accel? = null,
) : LineArtModel, AutoCloseable {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private val failedAtRuntime = EnumSet.noneOf(Accel::class.java)
    @Volatile private var runOptions: OrtSession.RunOptions? = null

    val specId: String get() = spec.id
    val specName: String get() = spec.name
    val isBundled: Boolean get() = spec.isBundled

    /** Requested provider. Changing it drops the current session; the next infer rebuilds it. */
    @Volatile var accel: Accel = accel ?: savedAccel(appContext) ?: Accel.XNNPACK
        set(value) {
            if (value == field) return
            field = value
            locked { failedAtRuntime.clear(); closeSession() }
        }

    /** Provider actually running (null until a session exists). */
    @Volatile var activeAccel: Accel? = null
        private set

    /** Why [accel] was not honoured (session creation or first run failed); null if it was. */
    @Volatile var lastFallbackReason: String? = null
        private set

    /** Kept for API compatibility with the settings UI; the resolution is controlled by [longSide]. */
    @Volatile var detail: Float = 0.5f

    /** Wall time of the last successful [infer] in ms (pre + run + post); null until one ran. */
    @Volatile var lastInferenceMs: Long? = null
        private set

    /** Pure session.run time of the last successful [infer] in ms (what the accelerator actually did). */
    @Volatile var lastRunMs: Long? = null
        private set

    @Volatile private var warming = false

    private var inBuf: ByteBuffer? = null
    private var xi0 = IntArray(0)
    private var xi1 = IntArray(0)
    private var xw = FloatArray(0)

    /** Persists and applies [a] as the user's choice. */
    fun selectAccel(a: Accel) {
        prefs.edit().putString(KEY_ACCEL, a.name).apply()
        accel = a
    }

    private fun threads() = min(4, max(1, Runtime.getRuntime().availableProcessors()))

    private inline fun <T> locked(waitMs: Long = LOCK_WAIT_MS, block: () -> T): T {
        if (!GLOBAL.tryLock(waitMs, TimeUnit.MILLISECONDS)) {
            throw IllegalStateException("AI engine busy (a previous run is stuck)")
        }
        try { return block() } finally { GLOBAL.unlock() }
    }

    /** Strict: throws if [a] cannot be created. Model weights are only held while the session is created. */
    private fun buildSession(e: OrtEnvironment, a: Accel): OrtSession {
        val opts = OrtSession.SessionOptions()
        try {
            val t = threads()
            opts.setIntraOpNumThreads(t)
            opts.setInterOpNumThreads(1)
            // fp16 graphs: skip the extended fusions/layout rewrites (copies = peak memory).
            opts.setOptimizationLevel(
                if (spec.fp16) OrtSession.SessionOptions.OptLevel.BASIC_OPT else OrtSession.SessionOptions.OptLevel.ALL_OPT,
            )
            // Lower peak memory: no pre-planned memory pattern, no caching arena.
            opts.setMemoryPatternOptimization(false)
            opts.setCPUArenaAllocator(false)
            when (a) {
                Accel.CPU -> Unit
                Accel.XNNPACK -> opts.addXnnpack(mapOf("intra_op_num_threads" to t.toString()))
                Accel.NNAPI_GPU -> opts.addNnapi(EnumSet.of(NNAPIFlags.USE_FP16, NNAPIFlags.CPU_DISABLED))
                Accel.NNAPI_NPU -> opts.addNnapi(EnumSet.of(NNAPIFlags.USE_NCHW, NNAPIFlags.CPU_DISABLED))
            }
            return when {
                modelFile != null -> e.createSession(modelFile.absolutePath, opts)
                spec.assetPath != null -> e.createSession(appContext.assets.open(spec.assetPath).use { it.readBytes() }, opts)
                else -> throw IllegalStateException("model ${spec.id} has no file")
            }
        } finally {
            opts.close()
        }
    }

    private fun chain(skipNnapi: Boolean): List<Accel> {
        val order = when {
            spec.fp16 -> listOf(Accel.CPU) // fp16: plain CPU only, never XNNPACK/NNAPI
            accel == Accel.CPU -> listOf(Accel.CPU)
            accel == Accel.XNNPACK -> listOf(Accel.XNNPACK, Accel.CPU)
            skipNnapi -> listOf(Accel.XNNPACK, Accel.CPU)
            else -> listOf(accel, Accel.XNNPACK, Accel.CPU)
        }
        return order.filter { it == Accel.CPU || it !in failedAtRuntime }
    }

    private fun closeSession() {
        try { session?.close() } catch (_: Throwable) {}
        session = null
        activeAccel = null
        if (owner === this) owner = null
    }

    /** Caller holds the global lock. */
    private fun ensureSession(skipNnapi: Boolean = false): Pair<OrtEnvironment, OrtSession> {
        val e = env ?: OrtEnvironment.getEnvironment().also { env = it }
        val cur = session
        if (cur != null) {
            val nn = activeAccel == Accel.NNAPI_GPU || activeAccel == Accel.NNAPI_NPU
            if (!(nn && skipNnapi)) return e to cur
            closeSession() // large input: leave NNAPI for this run
        }
        owner?.takeIf { it !== this }?.let { MlLog.i("closing session of ${it.spec.id} before creating ${spec.id}"); it.closeSession() }
        val t0 = System.nanoTime()
        var firstError: String? = null
        for (a in chain(skipNnapi)) {
            try {
                MlLog.i("session create ${spec.id} accel=$a")
                val s = buildSession(e, a)
                session = s
                activeAccel = a
                owner = this
                lastFallbackReason = if (a == accel) null else firstError
                MlLog.i("session ready ${spec.id} accel=$a (wanted $accel) in ${(System.nanoTime() - t0) / 1_000_000} ms")
                return e to s
            } catch (t: Throwable) {
                val msg = "$a failed: ${t.javaClass.simpleName}: ${t.message}"
                MlLog.w(msg)
                if (firstError == null) firstError = msg
            }
        }
        lastFallbackReason = firstError
        throw IllegalStateException("could not create ONNX session: $firstError")
    }

    /** Runs [block] on the shared inference thread; abandons it (and asks ORT to terminate) after [timeoutMs]. */
    private fun <T> guarded(timeoutMs: Long, what: String, block: () -> T): T {
        val ex = exec
        val fut = ex.submit(Callable { block() })
        try {
            return fut.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            MlLog.w("WATCHDOG $what ${spec.id} timed out after ${timeoutMs / 1000}s")
            terminateRun()
            fut.cancel(true)
            replaceExecutor(ex)
            throw InferenceTimeoutException("${spec.name} model timed out after ${timeoutMs / 1000}s")
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (e: InterruptedException) {
            terminateRun()
            fut.cancel(true)
            throw e
        }
    }

    private fun terminateRun() {
        val ro = runOptions ?: return
        try { ro.javaClass.getMethod("setTerminate", Boolean::class.javaPrimitiveType).invoke(ro, true) } catch (_: Throwable) {}
    }

    /**
     * Creates the session and runs a tiny dummy inference on a background thread so the first real
     * generation does not pay model load + kernel init. Skipped for fp16 models (heavy; built on demand).
     * Safe to call repeatedly; never throws.
     */
    fun warmUp() {
        if (warming || session != null || spec.fp16) return
        warming = true
        Thread({
            try {
                locked {
                    val (env, session) = ensureSession()
                    val name = session.inputNames.iterator().next()
                    val side = max(64, spec.sizeMultiple)
                    val buf = ByteBuffer.allocateDirect(3 * side * side * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                    OnnxTensor.createTensor(env, buf, longArrayOf(1, 3, side.toLong(), side.toLong())).use { t ->
                        session.run(mapOf(name to t)).close()
                    }
                }
            } catch (_: Throwable) {
                // Warm-up is best effort; infer() will surface real errors.
            } finally {
                warming = false
            }
        }, "onnx-warmup").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    override fun infer(argb: IntArray, w: Int, h: Int): FloatArray =
        guarded(TIMEOUT_MS, "infer") { locked { inferLocked(argb, w, h) } }

    private fun inferLocked(argb: IntArray, w: Int, h: Int): FloatArray {
        require(w > 0 && h > 0 && argb.size >= w * h) { "bad image" }
        val tStart = System.nanoTime()
        val (tw, th) = spec.targetSize(w, h, longSide)
        MlLog.i("infer start ${spec.id} ${w}x$h -> ${tw}x$th (${tw.toLong() * th} px, cap ${spec.maxPixels}) fp16=${spec.fp16}")
        val skipNnapi = spec.fp16 || tw.toLong() * th > NNAPI_MAX_PIXELS
        val fb = fillInput(argb, w, h, tw, th)
        val tPre = System.nanoTime()
        MlLog.i("infer preprocessed in ${(tPre - tStart) / 1_000_000} ms")
        val raw = FloatArray(tw * th)
        var attempts = 0
        while (true) {
            val (env, session) = ensureSession(skipNnapi)
            try {
                MlLog.i("run begin ${spec.id} accel=$activeAccel")
                runInto(env, session, fb, tw, th, raw)
                break
            } catch (t: Throwable) {
                val a = activeAccel
                // A provider that built fine but fails to run: block it and rebuild down the chain.
                if (a == null || a == Accel.CPU || t is InterruptedException || ++attempts > 3) throw t
                val msg = "$a run failed: ${t.javaClass.simpleName}: ${t.message}"
                MlLog.w(msg)
                failedAtRuntime.add(a)
                closeSession()
                lastFallbackReason = msg
            }
        }
        val tRun = System.nanoTime()
        inBuf = null // release the native input buffer between runs
        val out = FloatArray(tw * th)
        for (i in out.indices) out[i] = spec.inkFromOutput(raw[i])
        val res = if (tw == w && th == h) out else upsample(out, tw, th, w, h)
        val totalMs = (System.nanoTime() - tStart) / 1_000_000
        lastInferenceMs = totalMs
        lastRunMs = (tRun - tPre) / 1_000_000
        MlLog.i(
            "infer done ${spec.id} ${w}x$h -> ${tw}x$th accel=$activeAccel pre=${(tPre - tStart) / 1_000_000}ms " +
                "run=${(tRun - tPre) / 1_000_000}ms post=${(System.nanoTime() - tRun) / 1_000_000}ms total=${totalMs}ms",
        )
        return res
    }

    private fun runInto(env: OrtEnvironment, session: OrtSession, fb: FloatBuffer, tw: Int, th: Int, out: FloatArray) {
        val inputName = session.inputNames.iterator().next()
        fb.position(0)
        OrtSession.RunOptions().use { ro ->
            runOptions = ro
            try {
                OnnxTensor.createTensor(env, fb, longArrayOf(1, 3, th.toLong(), tw.toLong())).use { tensor ->
                    session.run(mapOf(inputName to tensor), ro).use { result ->
                        val ot = result.get(0) as OnnxTensor
                        val ofb = ot.floatBuffer
                        check(ofb.remaining() >= out.size) { "unexpected model output size ${ofb.remaining()}" }
                        ofb.get(out, 0, out.size)
                    }
                }
            } finally {
                runOptions = null
            }
        }
    }

    /**
     * Times CPU and CPU+XNNPACK (plus NNAPI only if [includeNnapi], never for fp16) on a flat gray [side]x[side]
     * input, one session at a time: creation must succeed, the first run is a warm-up, the second is timed.
     * Blocking; call off the main thread. Evicts any live session first (single-session rule).
     */
    fun benchmark(side: Int = 384, includeNnapi: Boolean = false): Map<Accel, Long> =
        guarded(BENCH_TIMEOUT_MS, "benchmark") { locked { benchmarkLocked(side, includeNnapi) } }

    private fun benchmarkLocked(side: Int, includeNnapi: Boolean): Map<Accel, Long> {
        val e = env ?: OrtEnvironment.getEnvironment().also { env = it }
        owner?.closeSession()
        closeSession()
        val m = spec.sizeMultiple
        val s = max(m, (side + m - 1) / m * m)
        val plane = s * s
        val fb = ByteBuffer.allocateDirect(3 * plane * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (i in 0 until 3 * plane) fb.put(i, 0.5f)
        val out = FloatArray(plane)
        val results = LinkedHashMap<Accel, Long>()
        val candidates = when {
            spec.fp16 -> listOf(Accel.CPU)
            includeNnapi -> Accel.values().toList()
            else -> listOf(Accel.CPU, Accel.XNNPACK)
        }
        for (a in candidates) {
            try {
                MlLog.i("benchmark ${spec.id} $a begin")
                buildSession(e, a).use { sess ->
                    runInto(e, sess, fb, s, s, out)
                    val t0 = System.nanoTime()
                    runInto(e, sess, fb, s, s, out)
                    val ms = (System.nanoTime() - t0) / 1_000_000
                    results[a] = ms
                    MlLog.i("benchmark ${spec.id} $a ${s}px: $ms ms")
                }
            } catch (t: Throwable) {
                MlLog.w("benchmark $a skipped: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        val ed = prefs.edit()
        for ((a, ms) in results) ed.putLong(KEY_BENCH_PREFIX + a.name, ms)
        ed.apply()
        return results
    }

    override fun close() {
        try {
            locked(CLOSE_WAIT_MS) {
                closeSession()
                inBuf = null
                // OrtEnvironment is a process-wide singleton; leave it open.
                env = null
            }
        } catch (t: IllegalStateException) {
            MlLog.w("close skipped: ${t.message}")
        }
    }

    /** Bilinear resize of [src] to [tw]x[th] + NCHW split into a direct buffer (exactly the model input size). */
    private fun fillInput(src: IntArray, w: Int, h: Int, tw: Int, th: Int): FloatBuffer {
        val plane = tw * th
        val bytes = 3 * plane * 4
        var bb = inBuf
        if (bb == null || bb.capacity() < bytes) {
            bb = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
            inBuf = bb
        }
        bb.clear()
        val fb = bb.asFloatBuffer()
        fb.limit(3 * plane)

        if (xi0.size < tw) { xi0 = IntArray(tw); xi1 = IntArray(tw); xw = FloatArray(tw) }
        val sx = w.toFloat() / tw
        val sy = h.toFloat() / th
        for (x in 0 until tw) {
            val fx = ((x + 0.5f) * sx - 0.5f).coerceIn(0f, (w - 1).toFloat())
            val x0 = floor(fx).toInt()
            xi0[x] = x0
            xi1[x] = min(w - 1, x0 + 1)
            xw[x] = fx - x0
        }
        val inv = spec.inputScale
        val off = spec.inputOffset
        for (y in 0 until th) {
            val fy = ((y + 0.5f) * sy - 0.5f).coerceIn(0f, (h - 1).toFloat())
            val y0 = floor(fy).toInt()
            val ay = fy - y0
            val r0 = y0 * w
            val r1 = min(h - 1, y0 + 1) * w
            val row = y * tw
            for (x in 0 until tw) {
                val ax = xw[x]
                val a0 = r0 + xi0[x]; val a1 = r0 + xi1[x]
                val b0 = r1 + xi0[x]; val b1 = r1 + xi1[x]
                val p00 = src[a0]; val p10 = src[a1]; val p01 = src[b0]; val p11 = src[b1]
                val i = row + x
                var sh = 16
                var c = 0
                while (c < 3) {
                    val a = ((p00 shr sh) and 255) * (1 - ax) + ((p10 shr sh) and 255) * ax
                    val b = ((p01 shr sh) and 255) * (1 - ax) + ((p11 shr sh) and 255) * ax
                    fb.put(c * plane + i, (a * (1 - ay) + b * ay) * inv + off)
                    sh -= 8
                    c++
                }
            }
        }
        fb.position(0)
        return fb
    }

    companion object {
        const val DEFAULT_LONG_SIDE = 768
        const val PREFS = "sketcher_ml"
        private const val KEY_ACCEL = "accel"
        private const val KEY_BENCH_PREFIX = "bench_ms_"
        private const val KEY_PREFS_VERSION = "prefs_version"
        private const val PREFS_VERSION = 2

        /** Inference watchdog (session creation + run). */
        const val TIMEOUT_MS = 45_000L
        private const val BENCH_TIMEOUT_MS = 60_000L
        private const val LOCK_WAIT_MS = 8_000L
        private const val CLOSE_WAIT_MS = 10_000L

        /** NNAPI (opt-in only) is skipped for inputs larger than this. */
        const val NNAPI_MAX_PIXELS = 512 * 512

        /** Process-wide: one ORT session at a time, one inference at a time. */
        private val GLOBAL = ReentrantLock()
        @Volatile private var owner: OnnxLineArtModel? = null
        @Volatile private var exec: ExecutorService = newExec()

        private fun newExec(): ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(r, "onnx-infer").apply { isDaemon = true }
        }

        private fun replaceExecutor(old: ExecutorService) {
            synchronized(this) {
                if (exec === old) {
                    old.shutdownNow()
                    exec = newExec()
                }
            }
        }

        /**
         * v2: NNAPI used to be auto-selected by a first-launch benchmark and crashed the system UI on fp16
         * models. Reset any stored NNAPI/auto choice to XNNPACK and drop old NNAPI timings. Idempotent.
         */
        fun migratePrefs(context: Context) {
            val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (p.getInt(KEY_PREFS_VERSION, 0) >= PREFS_VERSION) return
            val e = p.edit()
            if (p.getString(KEY_ACCEL, null)?.startsWith("NNAPI") == true) e.putString(KEY_ACCEL, "XNNPACK")
            val choice = p.getString("accel_choice", null)
            if (choice == null || choice == "auto" || choice.startsWith("nnapi")) e.putString("accel_choice", "xnnpack")
            e.remove(KEY_BENCH_PREFIX + "NNAPI_GPU").remove(KEY_BENCH_PREFIX + "NNAPI_NPU").remove("auto_accel_done")
            e.putInt(KEY_PREFS_VERSION, PREFS_VERSION).apply()
        }

        fun savedAccel(context: Context): Accel? =
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_ACCEL, null)?.let { n -> Accel.values().firstOrNull { it.name == n } }

        /** Last benchmark result (ms) stored for [a], if any. */
        fun savedBenchmarkMs(context: Context, a: Accel): Long? =
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_BENCH_PREFIX + a.name, -1L).takeIf { it >= 0 }
    }

    private fun upsample(src: FloatArray, sw: Int, sh: Int, w: Int, h: Int): FloatArray {
        val out = FloatArray(w * h)
        val sx = sw.toFloat() / w
        val sy = sh.toFloat() / h
        for (y in 0 until h) {
            val fy = ((y + 0.5f) * sy - 0.5f).coerceIn(0f, (sh - 1).toFloat())
            val y0 = floor(fy).toInt()
            val y1 = min(sh - 1, y0 + 1)
            val ay = fy - y0
            for (x in 0 until w) {
                val fx = ((x + 0.5f) * sx - 0.5f).coerceIn(0f, (sw - 1).toFloat())
                val x0 = floor(fx).toInt()
                val x1 = min(sw - 1, x0 + 1)
                val ax = fx - x0
                val a = src[y0 * sw + x0] * (1 - ax) + src[y0 * sw + x1] * ax
                val b = src[y1 * sw + x0] * (1 - ax) + src[y1 * sw + x1] * ax
                out[y * w + x] = a * (1 - ay) + b * ay
            }
        }
        return out
    }
}
