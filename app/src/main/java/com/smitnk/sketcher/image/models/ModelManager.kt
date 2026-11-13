package com.smitnk.sketcher.image.models

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Tracks which line-art models are usable and downloads the optional ones into [dir].
 * Blocking calls: run [download] on an IO dispatcher.
 */
class ModelManager(
    private val dir: File,
    private val specs: List<ModelSpec> = ModelSpecs.ALL,
    private val hasAsset: (String) -> Boolean = { true },
) {
    constructor(context: Context) : this(
        File(context.applicationContext.filesDir, "models"),
        ModelSpecs.ALL,
        { path -> assetExists(context.applicationContext, path) },
    )

    fun list(): List<ModelSpec> = specs

    fun spec(id: String): ModelSpec? = specs.firstOrNull { it.id == id }

    /** Local file of a downloaded model (may not exist yet); null for bundled models. */
    fun file(id: String): File? = spec(id)?.takeIf { !it.isBundled }?.let { File(dir, it.fileName) }

    fun isAvailable(id: String): Boolean {
        val s = spec(id) ?: return false
        s.assetPath?.let { return hasAsset(it) }
        val f = File(dir, s.fileName)
        return f.isFile && f.length() > 0
    }

    /**
     * Downloads [id] to dir/<fileName> via a temp file (renamed on success). [onProgress] gets
     * (bytesDone, bytesTotal); total is -1 when unknown. Throws IOException on any failure
     * (including sha256 mismatch); the temp file is removed. [shouldCancel] is polled per chunk.
     */
    @Throws(IOException::class)
    fun download(
        id: String,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        shouldCancel: () -> Boolean = { false },
    ): File {
        val s = spec(id) ?: throw IOException("unknown model $id")
        val url = s.downloadUrl ?: throw IOException("model $id is bundled")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create $dir")
        val dest = File(dir, s.fileName)
        val tmp = File(dir, s.fileName + ".part")
        tmp.delete()
        try {
            val conn = URL(url).openConnection()
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            if (conn is HttpURLConnection) {
                conn.instanceFollowRedirects = true
                val code = conn.responseCode
                if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code for $url")
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: (s.sizeBytes ?: -1L)
            var done = 0L
            onProgress(0L, total)
            conn.getInputStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var lastReport = 0L
                    while (true) {
                        if (shouldCancel()) throw InterruptedIOException("cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastReport >= 256 * 1024) { lastReport = done; onProgress(done, total) }
                    }
                }
            }
            if (done == 0L) throw IOException("empty download")
            s.sha256?.let { want ->
                val got = sha256Hex(tmp)
                if (!got.equals(want, ignoreCase = true)) throw IOException("sha256 mismatch (got $got)")
            }
            if (dest.exists() && !dest.delete()) throw IOException("cannot replace $dest")
            if (!tmp.renameTo(dest)) throw IOException("cannot move download into place")
            onProgress(done, if (total > 0) total else done)
            return dest
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }

    /** Deletes a downloaded model. Bundled models cannot be deleted (returns false). */
    fun delete(id: String): Boolean {
        val f = file(id) ?: return false
        File(dir, f.name + ".part").delete()
        return f.delete()
    }

    companion object {
        fun sha256Hex(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { ins ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        private fun assetExists(ctx: Context, path: String): Boolean = try {
            ctx.assets.open(path).close(); true
        } catch (_: IOException) { false }
    }
}
