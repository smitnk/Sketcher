package com.smitnk.sketcher.data

import android.graphics.Bitmap
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import com.smitnk.sketcher.look.LookSettings
import com.smitnk.sketcher.look.Paper
import com.smitnk.sketcher.look.PostFilter
import com.smitnk.sketcher.look.StrokeFeel
import com.smitnk.sketcher.model.LayerKind

/** Pure-Kotlin mirror of a stroke (brush stored as BrushType ordinal). */
class StrokeSnapshot(
    val points: FloatArray,
    val color: Int,
    val width: Float,
    val brush: Int,
    val alpha: Float,
)

class LayerSnapshot(
    val id: Long,
    val name: String,
    val visible: Boolean,
    val opacity: Float,
    val locked: Boolean,
    val inAnimation: Boolean,
    val animPriority: Int,
    val strokes: List<StrokeSnapshot>,
    /** LayerKind ordinal (3 = MANUAL). */
    val kind: Int = LayerKind.MANUAL.ordinal,
    /** v3: pencil-reveal ink texture as PNG bytes (null = none) and its reveal-stroke width. */
    val inkPng: ByteArray? = null,
    val revealWidthPx: Float = 0f,
)

/**
 * One page of the animation. [source] is the frame's reference photo (never encoded: the repository stores it
 * as frame_<id>.jpg next to the project file).
 */
class FrameSnapshot(
    val id: Long,
    val layers: List<LayerSnapshot>,
    val selectedIndex: Int,
    val backgroundVisible: Boolean,
    val backgroundOpacity: Float,
    /** Animation duration in seconds; <= 0 means "unknown / use default". */
    val animationSeconds: Float = 0f,
    val source: Bitmap? = null,
)

/**
 * A whole project. [frames] is the truth; [layers], [selectedIndex], [backgroundVisible], [backgroundOpacity] and
 * [animationSeconds] mirror the OPEN frame ([currentFrameIndex]) so single-frame code keeps working. A snapshot
 * built with the legacy constructor is one frame (id 1). Use [ofFrames] for several frames.
 */
class ProjectSnapshot(
    val width: Int,
    val height: Int,
    val layers: List<LayerSnapshot>,
    val selectedIndex: Int,
    val backgroundVisible: Boolean,
    val backgroundOpacity: Float,
    /** Animation duration in seconds of the open frame; <= 0 means "unknown / use default". */
    val animationSeconds: Float = 0f,
    val frames: List<FrameSnapshot> = listOf(
        FrameSnapshot(1L, layers, selectedIndex, backgroundVisible, backgroundOpacity, animationSeconds),
    ),
    val currentFrameIndex: Int = 0,
    /** v5: per-project Look (default for older files). */
    val look: LookSettings = LookSettings(),
) {
    /** Same project with the given reference photos attached (frame id -> bitmap). */
    fun withSources(sources: Map<Long, Bitmap?>): ProjectSnapshot = ofFrames(
        width, height,
        frames.map { f ->
            FrameSnapshot(f.id, f.layers, f.selectedIndex, f.backgroundVisible, f.backgroundOpacity, f.animationSeconds, sources[f.id] ?: f.source)
        },
        currentFrameIndex,
        look,
    )

    companion object {
        fun ofFrames(width: Int, height: Int, frames: List<FrameSnapshot>, currentFrameIndex: Int, look: LookSettings = LookSettings()): ProjectSnapshot {
            require(frames.isNotEmpty()) { "a project needs at least one frame" }
            val ci = currentFrameIndex.coerceIn(0, frames.lastIndex)
            val c = frames[ci]
            return ProjectSnapshot(
                width, height, c.layers, c.selectedIndex, c.backgroundVisible, c.backgroundOpacity, c.animationSeconds,
                frames, ci, look,
            )
        }
    }
}

/**
 * Binary format: "SKCH", version int, then fields (see write/read). v2 adds a per-layer kind byte; v3 adds an
 * optional per-layer ink PNG; v4 stores a list of frames (v1-v3 files load as a single frame); v5 appends the project Look after the frames
 * (older files load with the default Look).
 */
object ProjectCodec {
    const val VERSION = 5
    private const val MAX_INK_BYTES = 200_000_000
    private const val MAX_DIM = 8192
    private const val MIN_LAYER_BYTES = 20
    private const val MIN_STROKE_BYTES = 20
    private const val MIN_FRAME_BYTES = 25
    private const val MAX_FRAMES = 5_000
    private val MAGIC = byteArrayOf('S'.code.toByte(), 'K'.code.toByte(), 'C'.code.toByte(), 'H'.code.toByte())
    private const val MAX_LAYERS = 10_000
    private const val MAX_STROKES = 5_000_000
    private const val MAX_POINT_FLOATS = 50_000_000

    fun encode(s: ProjectSnapshot): ByteArray {
        val bos = ByteArrayOutputStream()
        val out = DataOutputStream(bos)
        out.write(MAGIC)
        out.writeInt(VERSION)
        out.writeInt(s.width)
        out.writeInt(s.height)
        out.writeInt(s.currentFrameIndex)
        out.writeInt(s.frames.size)
        for (f in s.frames) {
            out.writeLong(f.id)
            out.writeInt(f.selectedIndex)
            out.writeBoolean(f.backgroundVisible)
            out.writeFloat(f.backgroundOpacity)
            out.writeFloat(f.animationSeconds)
            writeLayers(out, f.layers)
        }
        writeLook(out, s.look)
        out.flush()
        return bos.toByteArray()
    }

    private fun writeLook(out: DataOutputStream, l: LookSettings) {
        out.writeByte(l.feel.ordinal)
        out.writeByte(l.paper.ordinal)
        out.writeBoolean(l.misregisterColour)
        out.writeByte(l.post.ordinal)
        out.writeBoolean(l.onTwos)
        out.writeInt(l.seed)
    }

    /** Unknown enum ordinals (a newer app wrote them) fall back to the default value. */
    private fun readLook(inp: DataInputStream): LookSettings {
        val feel = StrokeFeel.values().getOrElse(inp.readUnsignedByte()) { StrokeFeel.CLEAN }
        val paper = Paper.values().getOrElse(inp.readUnsignedByte()) { Paper.PLAIN }
        val misreg = inp.readBoolean()
        val post = PostFilter.values().getOrElse(inp.readUnsignedByte()) { PostFilter.NONE }
        val twos = inp.readBoolean()
        val seed = inp.readInt()
        return LookSettings(feel, paper, misreg, post, twos, seed)
    }

    private fun writeLayers(out: DataOutputStream, layers: List<LayerSnapshot>) {
        out.writeInt(layers.size)
        for (l in layers) {
            out.writeLong(l.id)
            out.writeUTF(l.name)
            out.writeBoolean(l.visible)
            out.writeFloat(l.opacity)
            out.writeBoolean(l.locked)
            out.writeBoolean(l.inAnimation)
            out.writeInt(l.animPriority)
            out.writeByte(l.kind)
            out.writeInt(l.strokes.size)
            for (st in l.strokes) {
                out.writeInt(st.color)
                out.writeFloat(st.width)
                out.writeInt(st.brush)
                out.writeFloat(st.alpha)
                out.writeInt(st.points.size)
                for (f in st.points) out.writeFloat(f)
            }
            val png = l.inkPng
            if (png != null && png.isNotEmpty()) {
                out.writeBoolean(true)
                out.writeFloat(l.revealWidthPx)
                out.writeInt(png.size)
                out.write(png)
            } else {
                out.writeBoolean(false)
            }
        }
    }

    /** v1 files had no kind: infer it from the names the generator used. */
    internal fun inferKind(name: String): LayerKind = when (name) {
        "Outline" -> LayerKind.AUTO_OUTLINE
        "Shading" -> LayerKind.AUTO_SHADING
        "Colour Fill" -> LayerKind.AUTO_FILL
        else -> LayerKind.MANUAL
    }

    /** @throws IOException on bad magic, unsupported version or corrupt data. */
    fun decode(bytes: ByteArray): ProjectSnapshot {
        val inp = DataInputStream(ByteArrayInputStream(bytes))
        val magic = ByteArray(4)
        inp.readFully(magic)
        if (!magic.contentEquals(MAGIC)) throw IOException("Not a Sketcher project")
        val version = inp.readInt()
        if (version !in 1..VERSION) throw IOException("Unsupported project version $version")
        val width = inp.readInt()
        val height = inp.readInt()
        if (width !in 1..MAX_DIM || height !in 1..MAX_DIM) throw IOException("Corrupt project size")
        if (version >= 4) {
            val current = inp.readInt()
            val frameCount = inp.readInt()
            if (frameCount !in 1..MAX_FRAMES || frameCount > inp.available() / MIN_FRAME_BYTES) throw IOException("Corrupt frame count")
            val frames = ArrayList<FrameSnapshot>(frameCount)
            val seen = HashSet<Long>()
            var maxId = 0L
            repeat(frameCount) {
                val id = inp.readLong()
                if (id > maxId) maxId = id
                val selected = inp.readInt()
                val bgVisible = inp.readBoolean()
                val bgOpacity = inp.readFloat()
                val animSeconds = inp.readFloat()
                val layers = readLayers(inp, version)
                // A corrupt file must not give two frames one id (they would share one photo file).
                val uniqueId = if (seen.add(id)) id else { maxId += 1; seen.add(maxId); maxId }
                frames.add(FrameSnapshot(uniqueId, layers, selected, bgVisible, bgOpacity, animSeconds))
            }
            val look = if (version >= 5) readLook(inp) else LookSettings()
            return ProjectSnapshot.ofFrames(width, height, frames, current, look)
        }
        val selected = inp.readInt()
        val bgVisible = inp.readBoolean()
        val bgOpacity = inp.readFloat()
        val animSeconds = inp.readFloat()
        val layers = readLayers(inp, version)
        return ProjectSnapshot(width, height, layers, selected, bgVisible, bgOpacity, animSeconds)
    }

    private fun readLayers(inp: DataInputStream, version: Int): List<LayerSnapshot> {
        val layerCount = inp.readInt()
        if (layerCount !in 0..MAX_LAYERS || layerCount > inp.available() / MIN_LAYER_BYTES) throw IOException("Corrupt layer count")
        val layers = ArrayList<LayerSnapshot>(layerCount)
        repeat(layerCount) {
            val id = inp.readLong()
            val name = inp.readUTF()
            val visible = inp.readBoolean()
            val opacity = inp.readFloat()
            val locked = inp.readBoolean()
            val inAnim = inp.readBoolean()
            val prio = inp.readInt()
            val kind = if (version >= 2) {
                inp.readUnsignedByte().takeIf { it < LayerKind.values().size } ?: LayerKind.MANUAL.ordinal
            } else {
                inferKind(name).ordinal
            }
            val strokeCount = inp.readInt()
            if (strokeCount !in 0..MAX_STROKES || strokeCount > inp.available() / MIN_STROKE_BYTES) throw IOException("Corrupt stroke count")
            val strokes = ArrayList<StrokeSnapshot>(minOf(strokeCount, 1024))
            repeat(strokeCount) {
                val color = inp.readInt()
                val w = inp.readFloat()
                val brush = inp.readInt()
                val alpha = inp.readFloat()
                val n = inp.readInt()
                if (n !in 0..MAX_POINT_FLOATS || n > inp.available() / 4) throw IOException("Corrupt point count")
                val pts = FloatArray(n)
                for (i in 0 until n) pts[i] = inp.readFloat()
                strokes.add(StrokeSnapshot(pts, color, w, brush, alpha))
            }
            var inkPng: ByteArray? = null
            var reveal = 0f
            if (version >= 3 && inp.readBoolean()) {
                reveal = inp.readFloat()
                val n = inp.readInt()
                if (n !in 1..MAX_INK_BYTES || n > inp.available()) throw IOException("Corrupt ink texture")
                inkPng = ByteArray(n)
                inp.readFully(inkPng)
            }
            layers.add(LayerSnapshot(id, name, visible, opacity, locked, inAnim, prio, strokes, kind, inkPng, reveal))
        }
        return layers
    }
}
