package com.smitnk.sketcher.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.smitnk.sketcher.model.Layer
import java.io.BufferedOutputStream
import java.io.OutputStream
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/** Renders the draw-along animation to a looping GIF89a using a pure-Kotlin encoder. */
object GifExporter {
    const val FPS = 12
    private const val MAX_LONG_SIDE = 540
    private const val HOLD_CS = 100 // extra hold on the last frame, 1 s

    /** Blocking; call off the main thread. See [VideoExporter.export] for parameters. */
    fun export(
        context: Context,
        width: Int,
        height: Int,
        layers: List<Layer>,
        durationSec: Float,
        baseName: String,
        onProgress: (Float) -> Unit,
        checkCancel: () -> Unit,
    ): String? {
        val (w, h) = fitSize(width, height, MAX_LONG_SIDE, 1)
        val target = OutputTarget.create(context, false, exportFileName(baseName, "gif"), "image/gif") ?: return null
        val s = min(w.toFloat() / width, h.toFloat() / height)
        val dx = (w - width * s) / 2f
        val dy = (h - height * s) / 2f
        val frames = ceil(durationSec * FPS).toInt().coerceAtLeast(1) + 1

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val pixels = IntArray(w * h)
        val indices = ByteArray(w * h)

        fun render(progress: Float) {
            canvas.drawColor(Color.WHITE)
            canvas.save()
            canvas.translate(dx, dy)
            canvas.scale(s, s)
            canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
            Renderer.drawPartial(canvas, width, height, layers, progress)
            canvas.restore()
            bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        }

        var out: OutputStream? = null
        var ok = false
        try {
            // The finished drawing contains every colour, so build one global palette from it.
            render(1f)
            val palette = GifQuantizer.palette(pixels)
            val lut = IntArray(32768) { -1 }

            val stream = BufferedOutputStream(target.openStream() ?: return null, 1 shl 16)
            out = stream
            writeHeader(stream, w, h, palette)
            val lzw = GifLzw(stream)

            for (i in 0 until frames) {
                checkCancel()
                render(if (frames > 1) i.toFloat() / (frames - 1) else 1f)
                GifQuantizer.map(pixels, indices, palette, lut)
                var delay = cs(i + 1) - cs(i)
                if (i == frames - 1) delay += HOLD_CS
                writeFrameHeader(stream, w, h, delay)
                lzw.encode(indices)
                onProgress((i + 1).toFloat() / frames)
            }
            stream.write(0x3B)
            stream.flush()
            ok = true
        } finally {
            try { out?.close() } catch (e: Exception) { ok = false }
            bmp.recycle()
            if (ok) target.commit() else target.abort()
        }
        return if (ok) target.displayPath else null
    }

    /** Start time of frame [i] in centiseconds, rounded so delays average exactly 100/12. */
    private fun cs(i: Int): Int = (i * 100.0 / FPS).roundToInt()

    private fun OutputStream.short(v: Int) {
        write(v and 0xFF)
        write((v shr 8) and 0xFF)
    }

    private fun writeHeader(o: OutputStream, w: Int, h: Int, palette: IntArray) {
        o.write("GIF89a".toByteArray(Charsets.US_ASCII))
        o.short(w)
        o.short(h)
        o.write(0xF7) // global colour table, 8 bits/colour, 256 entries
        o.write(0) // background index
        o.write(0) // pixel aspect ratio
        for (c in palette) {
            o.write((c shr 16) and 0xFF)
            o.write((c shr 8) and 0xFF)
            o.write(c and 0xFF)
        }
        // NETSCAPE2.0 looping extension: loop forever
        o.write(0x21)
        o.write(0xFF)
        o.write(11)
        o.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        o.write(3)
        o.write(1)
        o.short(0)
        o.write(0)
    }

    private fun writeFrameHeader(o: OutputStream, w: Int, h: Int, delayCs: Int) {
        // Graphic control extension: disposal "leave in place", no transparency
        o.write(0x21)
        o.write(0xF9)
        o.write(4)
        o.write(0x04)
        o.short(delayCs.coerceAtLeast(2))
        o.write(0)
        o.write(0)
        // Image descriptor, no local colour table
        o.write(0x2C)
        o.short(0)
        o.short(0)
        o.short(w)
        o.short(h)
        o.write(0)
    }
}

/** Median-cut palette (256 entries, 0 = white, 1 = black) over 5-bit-per-channel colours. */
internal object GifQuantizer {
    private class Box(var start: Int, var end: Int, var total: Long)

    private fun center(v: Int): Int = (v shl 3) or (v shr 2)
    private fun binColor(bin: Int): Int =
        (center((bin shr 10) and 31) shl 16) or (center((bin shr 5) and 31) shl 8) or center(bin and 31)

    private fun binOf(p: Int): Int = (((p shr 19) and 31) shl 10) or (((p shr 11) and 31) shl 5) or ((p shr 3) and 31)

    fun palette(pixels: IntArray): IntArray {
        val hist = IntArray(32768)
        for (p in pixels) hist[binOf(p)]++
        var n = 0
        val bins = IntArray(32768)
        for (b in 1 until 32767) if (hist[b] > 0) bins[n++] = b

        val pal = IntArray(256) // unused entries stay black
        pal[0] = 0xFFFFFF
        pal[1] = 0x000000
        if (n == 0) return pal

        var all = 0L
        for (i in 0 until n) all += hist[bins[i]]
        val boxes = ArrayList<Box>()
        boxes.add(Box(0, n, all))
        while (boxes.size < 254) {
            var best: Box? = null
            for (b in boxes) if (b.end - b.start > 1 && (best == null || b.total > best.total)) best = b
            val box = best ?: break
            // widest channel of the box
            var rMin = 31; var rMax = 0; var gMin = 31; var gMax = 0; var bMin = 31; var bMax = 0
            for (i in box.start until box.end) {
                val bin = bins[i]
                val r = (bin shr 10) and 31; val g = (bin shr 5) and 31; val b2 = bin and 31
                if (r < rMin) rMin = r; if (r > rMax) rMax = r
                if (g < gMin) gMin = g; if (g > gMax) gMax = g
                if (b2 < bMin) bMin = b2; if (b2 > bMax) bMax = b2
            }
            val shift = when {
                rMax - rMin >= gMax - gMin && rMax - rMin >= bMax - bMin -> 10
                gMax - gMin >= bMax - bMin -> 5
                else -> 0
            }
            val keys = IntArray(box.end - box.start)
            for (i in keys.indices) {
                val bin = bins[box.start + i]
                keys[i] = (((bin shr shift) and 31) shl 15) or bin
            }
            keys.sort()
            for (i in keys.indices) bins[box.start + i] = keys[i] and 0x7FFF
            var acc = 0L
            var split = box.start + 1
            for (i in box.start until box.end - 1) {
                acc += hist[bins[i]]
                split = i + 1
                if (acc * 2 >= box.total) break
            }
            boxes.add(Box(split, box.end, box.total - acc))
            box.end = split
            box.total = acc
        }

        for ((k, box) in boxes.withIndex()) {
            var r = 0L; var g = 0L; var b = 0L; var cnt = 0L
            for (i in box.start until box.end) {
                val c = binColor(bins[i])
                val wgt = hist[bins[i]].toLong()
                r += ((c shr 16) and 0xFF) * wgt
                g += ((c shr 8) and 0xFF) * wgt
                b += (c and 0xFF) * wgt
                cnt += wgt
            }
            if (cnt == 0L) continue
            pal[2 + k] = (((r / cnt).toInt()) shl 16) or (((g / cnt).toInt()) shl 8) or (b / cnt).toInt()
        }
        return pal
    }

    /** Maps ARGB [pixels] to palette indices; [lut] caches the nearest entry per 5-bit colour. */
    fun map(pixels: IntArray, out: ByteArray, palette: IntArray, lut: IntArray) {
        for (i in pixels.indices) {
            val bin = binOf(pixels[i])
            var idx = lut[bin]
            if (idx < 0) {
                idx = nearest(binColor(bin), palette)
                lut[bin] = idx
            }
            out[i] = idx.toByte()
        }
    }

    private fun nearest(c: Int, palette: IntArray): Int {
        val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
        var best = 0
        var bestD = Int.MAX_VALUE
        for (i in palette.indices) {
            val p = palette[i]
            val dr = r - ((p shr 16) and 0xFF); val dg = g - ((p shr 8) and 0xFF); val db = b - (p and 0xFF)
            val d = dr * dr + dg * dg + db * db
            if (d < bestD) {
                bestD = d
                best = i
                if (d == 0) break
            }
        }
        return best
    }
}

/** Variable-width GIF LZW writer (min code size 8) emitting data sub-blocks. */
private class GifLzw(private val out: OutputStream) {
    private val block = ByteArray(255)
    private var blockLen = 0
    private var accum = 0
    private var accBits = 0
    private var nBits = 9
    private var maxCode = 511
    private var freeEnt = 258
    private var clearFlag = false
    private val hashKeys = IntArray(HSIZE)
    private val hashVals = IntArray(HSIZE)

    fun encode(idx: ByteArray) {
        out.write(8)
        nBits = 9; maxCode = 511; freeEnt = 258; clearFlag = false
        accum = 0; accBits = 0; blockLen = 0
        hashKeys.fill(-1)
        emit(CLEAR)
        var ent = idx[0].toInt() and 0xFF
        for (i in 1 until idx.size) {
            val c = idx[i].toInt() and 0xFF
            val key = (ent shl 8) or c
            var h = (key * -1640531535) ushr 19
            var found = -1
            while (true) {
                val k = hashKeys[h]
                if (k == key) { found = hashVals[h]; break }
                if (k == -1) break
                h = (h + 1) and (HSIZE - 1)
            }
            if (found >= 0) {
                ent = found
                continue
            }
            emit(ent)
            ent = c
            if (freeEnt < 4096) {
                hashKeys[h] = key
                hashVals[h] = freeEnt++
            } else {
                hashKeys.fill(-1)
                freeEnt = 258
                clearFlag = true
                emit(CLEAR)
            }
        }
        emit(ent)
        emit(EOI)
        if (accBits > 0) putByte(accum and 0xFF)
        accum = 0; accBits = 0
        flushBlock()
        out.write(0)
    }

    private fun emit(code: Int) {
        accum = accum or (code shl accBits)
        accBits += nBits
        while (accBits >= 8) {
            putByte(accum and 0xFF)
            accum = accum ushr 8
            accBits -= 8
        }
        if (freeEnt > maxCode || clearFlag) {
            if (clearFlag) {
                nBits = 9
                maxCode = 511
                clearFlag = false
            } else {
                nBits++
                maxCode = if (nBits >= 12) 4096 else (1 shl nBits) - 1
            }
        }
    }

    private fun putByte(b: Int) {
        block[blockLen++] = b.toByte()
        if (blockLen == 255) flushBlock()
    }

    private fun flushBlock() {
        if (blockLen > 0) {
            out.write(blockLen)
            out.write(block, 0, blockLen)
            blockLen = 0
        }
    }

    private companion object {
        const val HSIZE = 8192
        const val CLEAR = 256
        const val EOI = 257
    }
}
