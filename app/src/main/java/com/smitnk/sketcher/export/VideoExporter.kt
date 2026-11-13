package com.smitnk.sketcher.export

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.Surface
import com.smitnk.sketcher.model.Layer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.floor
import kotlin.math.min

enum class AnimExportKind { VIDEO, GIF }

/** Output size with the long side limited to [maxLong]; both dims are multiples of [multiple]. */
internal fun fitSize(width: Int, height: Int, maxLong: Int, multiple: Int): Pair<Int, Int> {
    val scale = min(1f, maxLong.toFloat() / maxOf(width, height).coerceAtLeast(1))
    fun snap(v: Int): Int = (floor(v * scale / multiple).toInt() * multiple).coerceAtLeast(multiple)
    return snap(width) to snap(height)
}

internal fun exportFileName(baseName: String, extension: String): String {
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val safe = baseName.replace(Regex("[^A-Za-z0-9_-]"), "_")
    return "Sketcher_${safe}_$stamp.$extension"
}

/** A pending output file in MediaStore (API 29+) or in a legacy / app folder. */
internal class OutputTarget private constructor(
    private val context: Context,
    private val uri: Uri?,
    private val file: File?,
    private val mime: String,
    private val publicFile: Boolean,
    val displayPath: String,
) {
    /** Real file path, or null when the target is a MediaStore uri. */
    val path: String? get() = file?.absolutePath

    fun openStream(): OutputStream? =
        if (uri != null) context.contentResolver.openOutputStream(uri) else FileOutputStream(file!!)

    fun openFd(): ParcelFileDescriptor? =
        if (uri != null) context.contentResolver.openFileDescriptor(uri, "rw") else null

    fun commit() {
        if (uri != null) {
            val values = ContentValues()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
        } else if (file != null && publicFile) {
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mime), null)
        }
    }

    fun abort() {
        try {
            if (uri != null) context.contentResolver.delete(uri, null, null) else file?.delete()
        } catch (e: Exception) {
            // best effort
        }
    }

    companion object {
        fun create(context: Context, video: Boolean, fileName: String, mime: String): OutputTarget? {
            val relDir = if (video) "Movies/Sketcher" else "Pictures/Sketcher"
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, mime)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, relDir)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val collection = if (video) {
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    } else {
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    }
                    val uri = context.contentResolver.insert(collection, values) ?: return null
                    OutputTarget(context, uri, null, mime, false, "$relDir/$fileName")
                } else {
                    val type = if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
                    val legacyOk = context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                        PackageManager.PERMISSION_GRANTED
                    val publicDir = if (legacyOk) {
                        File(Environment.getExternalStoragePublicDirectory(type), "Sketcher")
                            .takeIf { it.isDirectory || it.mkdirs() }
                    } else {
                        null
                    }
                    val dir = publicDir ?: context.getExternalFilesDir(type) ?: return null
                    val f = File(dir, fileName)
                    OutputTarget(context, null, f, mime, publicDir != null, f.absolutePath)
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}

/** Renders the draw-along animation to an H.264 MP4 via MediaCodec (surface input) + MediaMuxer. */
object VideoExporter {
    const val FPS = 30
    private const val MAX_LONG_SIDE = 1080
    private const val HOLD_FRAMES = FPS // hold the finished drawing for 1 s

    /**
     * [layers] must be a snapshot (see SketchViewModel.snapshotLayers). Blocking; call off the main
     * thread. [checkCancel] should throw a CancellationException when the user cancelled.
     * Returns a readable location, or null on failure.
     */
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
        val (outW, outH) = fitSize(width, height, MAX_LONG_SIDE, 16)
        val target = OutputTarget.create(context, true, exportFileName(baseName, "mp4"), "video/mp4") ?: return null

        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var pfd: ParcelFileDescriptor? = null
        var surface: Surface? = null
        var muxerStarted = false
        var ok = false
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            format.setInteger(MediaFormat.KEY_BIT_RATE, (outW.toLong() * outH * 5).toInt().coerceIn(1_500_000, 12_000_000))
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec = enc
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = enc.createInputSurface()
            surface = inputSurface
            enc.start()

            val mux: MediaMuxer
            val p = target.path
            if (p != null) {
                mux = MediaMuxer(p, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            } else {
                pfd = target.openFd() ?: return null
                mux = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            }
            muxer = mux

            val info = MediaCodec.BufferInfo()
            var track = -1
            var written = 0L

            // Surface input stamps frames with wall-clock time, so we rewrite timestamps at 30 fps.
            fun drain(endOfStream: Boolean) {
                var idle = 0
                while (true) {
                    val idx = enc.dequeueOutputBuffer(info, if (endOfStream) 10_000L else 0L)
                    if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (!endOfStream) return
                        if (++idle > 1000) throw IOException("Video encoder timed out")
                    } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        track = mux.addTrack(enc.outputFormat)
                        mux.start()
                        muxerStarted = true
                    } else if (idx >= 0) {
                        idle = 0
                        val buf = enc.getOutputBuffer(idx)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
                        if (info.size > 0 && buf != null && muxerStarted) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            info.presentationTimeUs = written * 1_000_000L / FPS
                            written++
                            mux.writeSampleData(track, buf, info)
                        }
                        enc.releaseOutputBuffer(idx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                    }
                }
            }

            val animFrames = (durationSec * FPS).toInt().coerceAtLeast(1)
            val total = animFrames + 1 + HOLD_FRAMES
            val s = min(outW.toFloat() / width, outH.toFloat() / height)
            val dx = (outW - width * s) / 2f
            val dy = (outH - height * s) / 2f
            var useHardware = true

            for (i in 0 until total) {
                checkCancel()
                val progress = if (i >= animFrames) 1f else i.toFloat() / animFrames
                val canvas = if (useHardware) {
                    try {
                        inputSurface.lockHardwareCanvas()
                    } catch (e: Exception) {
                        useHardware = false
                        inputSurface.lockCanvas(null)
                    }
                } else {
                    inputSurface.lockCanvas(null)
                }
                try {
                    canvas.drawColor(Color.WHITE)
                    canvas.save()
                    canvas.translate(dx, dy)
                    canvas.scale(s, s)
                    canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
                    Renderer.drawPartial(canvas, width, height, layers, progress)
                    canvas.restore()
                } finally {
                    inputSurface.unlockCanvasAndPost(canvas)
                }
                drain(false)
                onProgress((i + 1).toFloat() / total)
            }

            enc.signalEndOfInputStream()
            drain(true)
            ok = true
        } finally {
            try { codec?.stop() } catch (e: Exception) { }
            try { codec?.release() } catch (e: Exception) { }
            try { surface?.release() } catch (e: Exception) { }
            try { if (muxerStarted) muxer?.stop() } catch (e: Exception) { ok = false }
            try { muxer?.release() } catch (e: Exception) { }
            try { pfd?.close() } catch (e: Exception) { }
            if (ok) target.commit() else target.abort()
        }
        return if (ok) target.displayPath else null
    }
}
