package com.smitnk.sketcher.image.pipeline

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * ONNX Runtime line-art model (informative-drawings). Input float32 [1,3,H,W] RGB 0..1,
 * output [1,1,H,W] where 1 = paper; ink = 1 - out. Throws on any failure so the pipeline can fall back.
 */
class OnnxLineArtModel(
    context: Context,
    private val assetPath: String = "models/lineart.onnx",
    /** Inference long side at normal detail; [HIGH_DETAIL_BONUS] px more when [detail] > 0.7. */
    private val longSide: Int = 512,
    /** Opt-in only: NNAPI is unreliable on some MediaTek drivers. Default is CPU (+XNNPACK). */
    private val useNnapi: Boolean = false,
    private val useXnnpack: Boolean = true,
) : LineArtModel, AutoCloseable {

    private val appContext = context.applicationContext
    private val lock = Any()
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null

    /** 0..1 detail setting; set before [infer] (>0.7 raises the inference resolution to 640). */
    @Volatile var detail: Float = 0.5f

    @Volatile private var warming = false

    // Reused across calls (guarded by lock).
    private var inBuf: ByteBuffer? = null
    private var xi0 = IntArray(0)
    private var xi1 = IntArray(0)
    private var xw = FloatArray(0)

    private fun ensureSession(): Pair<OrtEnvironment, OrtSession> {
        val e = env ?: OrtEnvironment.getEnvironment().also { env = it }
        val s = session ?: run {
            val bytes = appContext.assets.open(assetPath).use { it.readBytes() }
            val threads = min(4, max(1, Runtime.getRuntime().availableProcessors()))
            createSession(e, bytes, threads, xnnpack = useXnnpack && !useNnapi, nnapi = useNnapi)
                ?: createSession(e, bytes, threads, xnnpack = false, nnapi = false)
                ?: throw IllegalStateException("could not create ONNX session")
        }.also { session = it }
        return e to s
    }

    /** Returns null on failure so the caller can retry with plain CPU. */
    private fun createSession(e: OrtEnvironment, bytes: ByteArray, threads: Int, xnnpack: Boolean, nnapi: Boolean): OrtSession? =
        try {
            val opts = OrtSession.SessionOptions()
            opts.setIntraOpNumThreads(threads)
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            if (xnnpack) opts.addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
            if (nnapi) opts.addNnapi()
            e.createSession(bytes, opts)
        } catch (t: Throwable) {
            if (!xnnpack && !nnapi) throw t // plain CPU failed: genuine error
            null
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
                    val side = 64
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
        val (env, session) = ensureSession()
        val side = if (detail > 0.7f) longSide + HIGH_DETAIL_BONUS else longSide
        val scale = side.toFloat() / max(w, h)
        val tw = roundTo8(w * scale)
        val th = roundTo8(h * scale)
        val n = tw * th
        val fb = fillInput(argb, w, h, tw, th)
        val inputName = session.inputNames.iterator().next()
        val out = FloatArray(n)
        OnnxTensor.createTensor(env, fb, longArrayOf(1, 3, th.toLong(), tw.toLong())).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                val ot = result.get(0) as OnnxTensor
                val ofb = ot.floatBuffer
                check(ofb.remaining() >= n) { "unexpected model output size ${ofb.remaining()}" }
                ofb.get(out, 0, n)
            }
        }
        for (i in 0 until n) {
            val v = out[i]
            out[i] = 1f - (if (v < 0f) 0f else if (v > 1f) 1f else v)
        }
        if (tw == w && th == h) out else upsample(out, tw, th, w, h)
    }

    override fun close() = synchronized(lock) {
        try { session?.close() } catch (_: Throwable) {}
        session = null
        inBuf = null
        // OrtEnvironment is a process-wide singleton; leave it open.
        env = null
    }

    private fun roundTo8(v: Float): Int = max(8, (v / 8f).roundToInt() * 8)

    /** Bilinear resize + NCHW split straight into a reused direct buffer (no boxing, no per-call big arrays). */
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
        val inv = 1f / 255f
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
                    fb.put(c * plane + i, (a * (1 - ay) + b * ay) * inv)
                    sh -= 8
                    c++
                }
            }
        }
        fb.position(0)
        return fb
    }

    private companion object {
        const val HIGH_DETAIL_BONUS = 128
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
