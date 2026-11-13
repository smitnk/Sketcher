package com.smitnk.sketcher.image.pipeline

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
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
    private val longSide: Int = 512,
) : LineArtModel, AutoCloseable {

    private val appContext = context.applicationContext
    private val lock = Any()
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null

    private fun ensureSession(): Pair<OrtEnvironment, OrtSession> {
        val e = env ?: OrtEnvironment.getEnvironment().also { env = it }
        val s = session ?: run {
            val bytes = appContext.assets.open(assetPath).use { it.readBytes() }
            val opts = OrtSession.SessionOptions()
            opts.setIntraOpNumThreads(min(4, max(1, Runtime.getRuntime().availableProcessors())))
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            e.createSession(bytes, opts).also { session = it }
        }
        return e to s
    }

    override fun infer(argb: IntArray, w: Int, h: Int): FloatArray = synchronized(lock) {
        require(w > 0 && h > 0 && argb.size >= w * h) { "bad image" }
        val (env, session) = ensureSession()
        val scale = longSide.toFloat() / max(w, h)
        val tw = roundTo8(w * scale)
        val th = roundTo8(h * scale)
        val input = toNchw(argb, w, h, tw, th)
        val inputName = session.inputNames.iterator().next()
        val out = FloatArray(tw * th)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, th.toLong(), tw.toLong())).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                val ot = result.get(0) as OnnxTensor
                val fb = ot.floatBuffer
                check(fb.remaining() >= tw * th) { "unexpected model output size ${fb.remaining()}" }
                for (i in 0 until tw * th) {
                    out[i] = 1f - fb.get(fb.position() + i).coerceIn(0f, 1f)
                }
            }
        }
        if (tw == w && th == h) out else upsample(out, tw, th, w, h)
    }

    override fun close() = synchronized(lock) {
        try { session?.close() } catch (_: Throwable) {}
        session = null
        // OrtEnvironment is a process-wide singleton; leave it open.
        env = null
    }

    private fun roundTo8(v: Float): Int = max(8, (v / 8f).roundToInt() * 8)

    private fun toNchw(src: IntArray, w: Int, h: Int, tw: Int, th: Int): FloatArray {
        val plane = tw * th
        val out = FloatArray(3 * plane)
        val sx = w.toFloat() / tw
        val sy = h.toFloat() / th
        for (y in 0 until th) {
            val fy = ((y + 0.5f) * sy - 0.5f).coerceIn(0f, (h - 1).toFloat())
            val y0 = floor(fy).toInt()
            val y1 = min(h - 1, y0 + 1)
            val ay = fy - y0
            for (x in 0 until tw) {
                val fx = ((x + 0.5f) * sx - 0.5f).coerceIn(0f, (w - 1).toFloat())
                val x0 = floor(fx).toInt()
                val x1 = min(w - 1, x0 + 1)
                val ax = fx - x0
                val p00 = src[y0 * w + x0]; val p10 = src[y0 * w + x1]
                val p01 = src[y1 * w + x0]; val p11 = src[y1 * w + x1]
                val i = y * tw + x
                for (c in 0 until 3) {
                    val sh = 16 - 8 * c
                    val a = ((p00 shr sh) and 255) * (1 - ax) + ((p10 shr sh) and 255) * ax
                    val b = ((p01 shr sh) and 255) * (1 - ax) + ((p11 shr sh) and 255) * ax
                    out[c * plane + i] = (a * (1 - ay) + b * ay) / 255f
                }
            }
        }
        return out
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
