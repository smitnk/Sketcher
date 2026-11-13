package com.smitnk.sketcher.image.pipeline

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import android.content.Context
import android.util.Log
import com.smitnk.sketcher.image.models.ModelSpec
import com.smitnk.sketcher.image.models.ModelSpecs
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.EnumSet
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Execution provider preference. NNAPI_* differ only in flags: ORT cannot pick the NNAPI device. */
enum class Accel { CPU, XNNPACK, NNAPI_GPU, NNAPI_NPU }

/**
 * ONNX Runtime line-art model driven by a [ModelSpec] (normalisation, output polarity, size multiple).
 * [infer] returns an ink map (1 = ink). Weights come from [modelFile] if given, else the spec's bundled asset.
 * Throws on failure so the pipeline can fall back.
 */
class OnnxLineArtModel(
    context: Context,
    private val spec: ModelSpec = ModelSpecs.INFORMATIVE,
    private val modelFile: File? = null,
    /** Inference long side (768 default; 1024 allowed). Rounded to the spec's size multiple. */
    @Volatile var longSide: Int = DEFAULT_LONG_SIDE,
    /** Null = last persisted choice, else XNNPACK. */
    accel: Accel? = null,
) : LineArtModel, AutoCloseable {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var modelBytes: ByteArray? = null
    private val failedAtRuntime = EnumSet.noneOf(Accel::class.java)

    /** Requested provider. Changing it drops the current session; the next infer rebuilds it. */
    @Volatile var accel: Accel = accel ?: savedAccel(appContext) ?: Accel.XNNPACK
        set(value) {
            if (value == field) return
            field = value
            synchronized(lock) { failedAtRuntime.clear(); closeSession() }
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

    private fun loadBytes(): ByteArray = modelBytes ?: run {
        val b = when {
            modelFile != null -> modelFile.readBytes()
            spec.assetPath != null -> appContext.assets.open(spec.assetPath).use { it.readBytes() }
            else -> throw IllegalStateException("model ${spec.id} has no file")
        }
        modelBytes = b
        b
    }

    /** Strict: throws if [a] cannot be created. */
    private fun buildSession(e: OrtEnvironment, bytes: ByteArray, a: Accel): OrtSession {
        val opts = OrtSession.SessionOptions()
        try {
            val t = threads()
            opts.setIntraOpNumThreads(t)
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            when (a) {
                Accel.CPU -> Unit
                Accel.XNNPACK -> opts.addXnnpack(mapOf("intra_op_num_threads" to t.toString()))
                Accel.NNAPI_GPU -> opts.addNnapi(EnumSet.of(NNAPIFlags.USE_FP16, NNAPIFlags.CPU_DISABLED))
                Accel.NNAPI_NPU -> opts.addNnapi(EnumSet.of(NNAPIFlags.USE_NCHW, NNAPIFlags.CPU_DISABLED))
            }
            return e.createSession(bytes, opts)
        } finally {
            opts.close()
        }
    }

    private fun chain(): List<Accel> {
        val order = when (accel) {
            Accel.CPU -> listOf(Accel.CPU)
            Accel.XNNPACK -> listOf(Accel.XNNPACK, Accel.CPU)
            else -> listOf(accel, Accel.XNNPACK, Accel.CPU)
        }
        return order.filter { it == Accel.CPU || it !in failedAtRuntime }
    }

    private fun closeSession() {
        try { session?.close() } catch (_: Throwable) {}
        session = null
        activeAccel = null
    }

    /** Caller holds [lock]. */
    private fun ensureSession(): Pair<OrtEnvironment, OrtSession> {
        val e = env ?: OrtEnvironment.getEnvironment().also { env = it }
        session?.let { return e to it }
        val t0 = System.nanoTime()
        val bytes = loadBytes()
        var firstError: String? = null
        for (a in chain()) {
            try {
                val s = buildSession(e, bytes, a)
                session = s
                activeAccel = a
                lastFallbackReason = if (a == accel) null else firstError
                Log.i(TAG, "session ${spec.id} accel=$a (wanted $accel) in ${(System.nanoTime() - t0) / 1_000_000} ms")
                return e to s
            } catch (t: Throwable) {
                val msg = "$a failed: ${t.javaClass.simpleName}: ${t.message}"
                Log.w(TAG, msg)
                if (firstError == null) firstError = msg
            }
        }
        lastFallbackReason = firstError
        throw IllegalStateException("could not create ONNX session: $firstError")
    }

    /**
     * Creates the session and runs a tiny dummy inference on a background thread so the first real
     * generation does not pay model load + kernel init. Safe to call repeatedly; never throws.
     */
    fun warmUp() {
        if (warming || session != null) return
        warming = true
        Thread({
            try {
                synchronized(lock) {
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

    override fun infer(argb: IntArray, w: Int, h: Int): FloatArray = synchronized(lock) {
        require(w > 0 && h > 0 && argb.size >= w * h) { "bad image" }
        val tStart = System.nanoTime()
        val (tw, th) = spec.targetSize(w, h, longSide)
        // NNAPI only accelerates fixed shapes: letterbox (white) into a square canvas so the shape never varies.
        // Fallback providers reuse the same canvas, which is harmless (white paper = no ink).
        val square = accel == Accel.NNAPI_GPU || accel == Accel.NNAPI_NPU
        val cw = if (square) max(tw, th) else tw
        val ch = if (square) max(tw, th) else th
        val ox = (cw - tw) / 2
        val oy = (ch - th) / 2
        val fb = fillInput(argb, w, h, tw, th, cw, ch, ox, oy)
        val tPre = System.nanoTime()
        val raw = FloatArray(cw * ch)
        var attempts = 0
        while (true) {
            val (env, session) = ensureSession()
            try {
                runInto(env, session, fb, cw, ch, raw)
                break
            } catch (t: Throwable) {
                val a = activeAccel
                // A provider that built fine but fails to run: block it and rebuild down the chain.
                if (a == null || a == Accel.CPU || ++attempts > 3) throw t
                val msg = "$a run failed: ${t.javaClass.simpleName}: ${t.message}"
                Log.w(TAG, msg)
                failedAtRuntime.add(a)
                closeSession()
                lastFallbackReason = msg
            }
        }
        val tRun = System.nanoTime()
        // Crop the content window out of the (possibly padded) output and convert to ink.
        val out = FloatArray(tw * th)
        for (y in 0 until th) {
            val src = (y + oy) * cw + ox
            val dst = y * tw
            for (x in 0 until tw) out[dst + x] = spec.inkFromOutput(raw[src + x])
        }
        val res = if (tw == w && th == h) out else upsample(out, tw, th, w, h)
        val totalMs = (System.nanoTime() - tStart) / 1_000_000
        lastInferenceMs = totalMs
        Log.i(
            TAG,
            "infer ${spec.id} ${w}x$h -> ${tw}x$th (canvas ${cw}x$ch) accel=$activeAccel pre=${(tPre - tStart) / 1_000_000}ms " +
                "run=${(tRun - tPre) / 1_000_000}ms post=${(System.nanoTime() - tRun) / 1_000_000}ms",
        )
        res
    }

    private fun runInto(env: OrtEnvironment, session: OrtSession, fb: FloatBuffer, tw: Int, th: Int, out: FloatArray) {
        val inputName = session.inputNames.iterator().next()
        fb.position(0)
        OnnxTensor.createTensor(env, fb, longArrayOf(1, 3, th.toLong(), tw.toLong())).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                val ot = result.get(0) as OnnxTensor
                val ofb = ot.floatBuffer
                check(ofb.remaining() >= out.size) { "unexpected model output size ${ofb.remaining()}" }
                ofb.get(out, 0, out.size)
            }
        }
    }

    /**
     * Times every [Accel] on a flat gray [side]x[side] input: session creation must succeed, the first run
     * is a warm-up, and the second run's milliseconds are reported. Failing providers are skipped.
     * Blocking (seconds); call off the main thread. Does not touch the active session.
     */
    fun benchmark(side: Int = 512): Map<Accel, Long> = synchronized(lock) {
        val e = env ?: OrtEnvironment.getEnvironment().also { env = it }
        val bytes = loadBytes()
        val m = spec.sizeMultiple
        val s = max(m, (side + m - 1) / m * m)
        val plane = s * s
        val fb = ByteBuffer.allocateDirect(3 * plane * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (i in 0 until 3 * plane) fb.put(i, 0.5f)
        val out = FloatArray(plane)
        val results = LinkedHashMap<Accel, Long>()
        for (a in Accel.values()) {
            try {
                buildSession(e, bytes, a).use { sess ->
                    runInto(e, sess, fb, s, s, out)
                    val t0 = System.nanoTime()
                    runInto(e, sess, fb, s, s, out)
                    val ms = (System.nanoTime() - t0) / 1_000_000
                    results[a] = ms
                    Log.i(TAG, "benchmark ${spec.id} $a ${s}px: $ms ms")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "benchmark $a skipped: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        for ((a, ms) in results) prefs.edit().putLong(KEY_BENCH_PREFIX + a.name, ms).apply()
        results
    }

    /** Runs [benchmark], selects (and persists) the fastest provider, returns it (null if none ran). */
    fun autoSelectAccel(): Accel? {
        val best = benchmark().minByOrNull { it.value }?.key ?: return null
        selectAccel(best)
        return best
    }

    override fun close() = synchronized(lock) {
        closeSession()
        inBuf = null
        modelBytes = null
        // OrtEnvironment is a process-wide singleton; leave it open.
        env = null
    }

    /**
     * Bilinear resize of [src] to [tw]x[th] + NCHW split into a reused direct buffer, placed at ([ox],[oy]) of a
     * [cw]x[ch] canvas. Canvas area outside the content is white (paper).
     */
    private fun fillInput(src: IntArray, w: Int, h: Int, tw: Int, th: Int, cw: Int, ch: Int, ox: Int, oy: Int): FloatBuffer {
        val plane = cw * ch
        val bytes = 3 * plane * 4
        var bb = inBuf
        if (bb == null || bb.capacity() < bytes) {
            bb = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
            inBuf = bb
        }
        bb.clear()
        val fb = bb.asFloatBuffer()
        fb.limit(3 * plane)
        if (cw != tw || ch != th) {
            val white = 255f * spec.inputScale + spec.inputOffset
            for (i in 0 until 3 * plane) fb.put(i, white)
        }

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
            val row = (y + oy) * cw + ox
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
        private const val TAG = "SketcherML"

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
