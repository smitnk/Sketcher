package com.smitnk.sketcher.look

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.smitnk.sketcher.anim.ArrivalBaker
import com.smitnk.sketcher.anim.ArrivalMap
import com.smitnk.sketcher.model.Layer
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * "GPU reveal (beta)": uncovers a pencil-reveal ink texture with one AGSL pass (no saveLayer) from a baked arrival map
 * (see [ArrivalBaker]). Only used on API 33+ with a hardware canvas; everything else keeps the saveLayer mask path.
 * See docs/gpu-reveal.md.
 */
object ArrivalReveal {
    private const val TAG = "Reveal"
    private const val MAX_GRID = 1024
    private const val MAX_ENTRIES = 6
    private const val FEATHER_PX = 1.5f

    /** Settings toggle ("GPU reveal (beta)"); default ON, effective on API 33+ only. */
    var enabled by mutableStateOf(true)

    @Volatile private var lastPath = ""
    fun notePath(path: String) {
        if (path != lastPath) { lastPath = path; Log.i(TAG, "REVEAL_PATH=$path") }
    }

    private class Entry(val sig: Long) { @Volatile var bitmap: Bitmap? = null; var shader: Any? = null }
    private val cache = object : LinkedHashMap<Long, Entry>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Entry>?) = size > MAX_ENTRIES
    }
    private val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "arrival-bake").apply { isDaemon = true } }
    private val rt = object : ThreadLocal<Holder>() { override fun initialValue() = Holder() }
    private class Holder { val paint = Paint(); var shader: Any? = null; var forEntry: Entry? = null; var forInk: Bitmap? = null }

    private fun signature(layer: Layer, w: Int, h: Int): Long {
        var s = layer.id * 31 + layer.inkRev
        val st = layer.strokes
        s = s * 31 + st.size
        s = s * 31 + java.lang.Float.floatToIntBits(layer.totalLength())
        s = s * 31 + java.lang.Float.floatToIntBits(layer.revealWidthPx)
        s = s * 31 + w * 7919 + h
        if (st.size > 0) s = s * 31 + System.identityHashCode(st[0].points) + System.identityHashCode(st[st.size - 1].points)
        return s
    }

    /**
     * Draws [ink] revealed up to [budget] (length) into [canvas] if the GPU path is usable now; false = caller uses
     * the saveLayer path (not API 33+, toggle off, software canvas, or the arrival map is still being baked).
     */
    fun tryDraw(canvas: Canvas, layer: Layer, ink: Bitmap, width: Int, height: Int, budget: Float, rect: RectF): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !enabled || !canvas.isHardwareAccelerated) {
            notePath("cpu"); return false
        }
        val total = layer.totalLength()
        if (total <= 0f) return false
        val sig = signature(layer, width, height)
        var e: Entry?
        synchronized(cache) {
            e = cache[layer.id]
            if (e == null || e!!.sig != sig) {
                val ne = Entry(sig); cache[layer.id] = ne; e = ne
                val pts = ArrayList<FloatArray>(layer.strokes.size); val lens = FloatArray(layer.strokes.size)
                for ((i, st) in layer.strokes.withIndex()) { pts.add(st.points); lens[i] = st.length }
                val radius = max(1f, layer.revealWidthPx) / 2f
                pool.execute { runCatching { ne.bitmap = bakeBitmap(width, height, pts, lens, radius) }.onFailure { Log.w(TAG, "bake failed", it) } }
            }
        }
        val arrival = e!!.bitmap ?: run { notePath("cpu"); return false }
        draw(canvas, e!!, arrival, ink, width, height, budget / total, total, rect)
        notePath("gpu")
        return true
    }

    private fun bakeBitmap(w: Int, h: Int, pts: List<FloatArray>, lens: FloatArray, radius: Float): Bitmap {
        val s = min(1f, MAX_GRID.toFloat() / max(w, h))
        val gw = max(1, ceil(w * s).toInt()); val gh = max(1, ceil(h * s).toInt())
        val m = ArrivalBaker.bake(gw, gh, gw.toFloat() / w, pts, lens, radius)
        val px = IntArray(gw * gh)
        val k = 65534f / m.total
        for (i in px.indices) {
            val a = m.data[i]
            val q = if (a == ArrivalMap.NEVER) 65535 else (a * k).roundToInt().coerceIn(0, 65534)
            px[i] = (255 shl 24) or ((q ushr 8) shl 16) or ((q and 255) shl 8)
        }
        return Bitmap.createBitmap(gw, gh, Bitmap.Config.ARGB_8888).also { it.setPixels(px, 0, gw, 0, 0, gw, gh) }
    }

    private const val AGSL = """
uniform shader ink;
uniform shader arrival;
uniform float allow;
uniform float feather;
uniform float2 grid;
half4 main(float2 p) {
    float2 q = (floor(p * grid) + 0.5) / grid;
    half4 c = arrival.eval(q);
    float v = (floor(c.r * 255.0 + 0.5) * 256.0 + floor(c.g * 255.0 + 0.5)) / 65535.0;
    float m = 1.0 - smoothstep(allow - feather, allow, v);
    return ink.eval(p) * half(m);
}"""

    @RequiresApi(33)
    private fun draw(canvas: Canvas, e: Entry, arrival: Bitmap, ink: Bitmap, w: Int, h: Int, allow: Float, total: Float, rect: RectF) {
        val hd = rt.get()!!
        if (hd.shader == null || hd.forEntry !== e || hd.forInk !== ink) {
            val sh = (hd.shader as? RuntimeShader) ?: RuntimeShader(AGSL)
            val inkSh = BitmapShader(ink, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            inkSh.setLocalMatrix(Matrix().apply { setScale(w.toFloat() / ink.width, h.toFloat() / ink.height) })
            val arrSh = BitmapShader(arrival, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            arrSh.setLocalMatrix(Matrix().apply { setScale(w.toFloat() / arrival.width, h.toFloat() / arrival.height) })
            sh.setInputShader("ink", inkSh)
            sh.setInputShader("arrival", arrSh)
            sh.setFloatUniform("grid", arrival.width.toFloat() / w, arrival.height.toFloat() / h)
            hd.shader = sh; hd.forEntry = e; hd.forInk = ink
            hd.paint.shader = sh
        }
        val sh = hd.shader as RuntimeShader
        sh.setFloatUniform("allow", allow)
        sh.setFloatUniform("feather", FEATHER_PX / total)
        hd.paint.shader = sh
        canvas.drawRect(rect, hd.paint)
    }
}
